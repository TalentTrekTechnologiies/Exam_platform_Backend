package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.dto.UploadReport;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.QuestionRepository;
import com.Exam.Exam_System.repository.SectionRepository;
import com.Exam.Exam_System.util.CsvParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class QuestionService {

    private static final Set<String> VALID_ANSWERS = Set.of("A", "B", "C", "D");

    private final QuestionRepository questionRepository;
    private final SectionRepository sectionRepository;
    private final ExamRepository examRepository;

    public QuestionService(QuestionRepository questionRepository,
                           SectionRepository sectionRepository,
                           ExamRepository examRepository) {
        this.questionRepository = questionRepository;
        this.sectionRepository = sectionRepository;
        this.examRepository = examRepository;
    }

    /**
     * Fills in the exam's own marking scheme for any question that didn't state
     * one.
     *
     * A source document or CSV frequently omits marks entirely, and inventing a
     * scheme out of nothing would be wrong. But the admin already stated their
     * scheme on the Create Exam screen — falling back to it is plainly what
     * they meant, and beats silently marking a 4-mark EAMCET paper at 1 mark a
     * question with no penalty, which is what happened before: those settings
     * reached the server and were dropped on the floor.
     */
    private void applyExamDefaults(Question q, Exam exam) {
        if (exam == null) return;
        if (q.getMarks() == null && exam.getDefaultMarks() != null) {
            q.setMarks(exam.getDefaultMarks());
        }
        if (q.getNegativeMarks() == null && exam.getDefaultNegativeMarks() != null) {
            q.setNegativeMarks(exam.getDefaultNegativeMarks());
        }
    }

    @Transactional(readOnly = true)
    public List<Question> getQuestionsByExam(Long examId) {
        return questionRepository.findByExamId(examId);
    }

    @Transactional
    public Question addQuestion(Question question) {
        // The exam's marking scheme has to reach questions added one at a time
        // as well, not only imported ones. Wiring it into the import paths but
        // not this one made the same paper mark differently depending on how
        // its questions happened to be entered — the kind of inconsistency
        // nobody notices until results are out.
        if (question.getExamId() != null) {
            applyExamDefaults(question, examRepository.findById(question.getExamId()).orElse(null));
        }
        validate(question);
        return questionRepository.save(question);
    }

    @Transactional
    public Question updateQuestion(Long id, Question updated) {
        Question q = questionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Question not found"));

        q.setQuestionText(updated.getQuestionText());
        q.setOptionA(updated.getOptionA());
        q.setOptionB(updated.getOptionB());
        q.setOptionC(updated.getOptionC());
        q.setOptionD(updated.getOptionD());
        q.setCorrectAnswer(updated.getCorrectAnswer());
        q.setMarks(updated.getMarks());
        q.setNegativeMarks(updated.getNegativeMarks());
        q.setSectionId(updated.getSectionId());
        q.setQuestionImage(updated.getQuestionImage());
        q.setOptionAImage(updated.getOptionAImage());
        q.setOptionBImage(updated.getOptionBImage());
        q.setOptionCImage(updated.getOptionCImage());
        q.setOptionDImage(updated.getOptionDImage());

        validate(q);
        return questionRepository.save(q);
    }

    @Transactional
    public void deleteQuestion(Long id) {
        if (!questionRepository.existsById(id)) {
            throw new NoSuchElementException("Question not found");
        }
        questionRepository.deleteById(id);
    }

    /**
     * Rejects questions that would be unanswerable or ungradable once live.
     * Catching this at authoring time is the difference between a bad row and a
     * disputed exam.
     */
    private void validate(Question q) {
        if (q.getExamId() == null) {
            throw new IllegalArgumentException("A question must belong to an exam.");
        }
        boolean hasText = q.getQuestionText() != null && !q.getQuestionText().isBlank();
        boolean hasImage = q.getQuestionImage() != null && !q.getQuestionImage().isBlank();
        if (!hasText && !hasImage) {
            throw new IllegalArgumentException("A question needs text or an image.");
        }

        String correct = q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer().trim().toUpperCase();
        if (!VALID_ANSWERS.contains(correct)) {
            throw new IllegalArgumentException("Correct answer must be A, B, C or D.");
        }
        q.setCorrectAnswer(correct);

        // The keyed option must actually exist, or the question is unscoreable.
        String keyedText = switch (correct) {
            case "A" -> q.getOptionA();
            case "B" -> q.getOptionB();
            case "C" -> q.getOptionC();
            default -> q.getOptionD();
        };
        String keyedImage = switch (correct) {
            case "A" -> q.getOptionAImage();
            case "B" -> q.getOptionBImage();
            case "C" -> q.getOptionCImage();
            default -> q.getOptionDImage();
        };
        boolean keyedPresent = (keyedText != null && !keyedText.isBlank())
                || (keyedImage != null && !keyedImage.isBlank());
        if (!keyedPresent) {
            throw new IllegalArgumentException("Option " + correct + " is marked correct but is empty.");
        }

        if (q.getMarks() == null) q.setMarks(1);
        if (q.getMarks() <= 0) throw new IllegalArgumentException("Marks must be greater than zero.");
        if (q.getNegativeMarks() == null) q.setNegativeMarks(0.0);
        if (q.getNegativeMarks() < 0) q.setNegativeMarks(Math.abs(q.getNegativeMarks()));
    }

    /**
     * Bulk import.
     *
     * Columns: questionText, optionA, optionB, optionC, optionD, correctAnswer,
     *          [marks], [negativeMarks], [sectionName]
     *
     * Reports every rejected row back to the admin rather than logging to stdout.
     */
    @Transactional
    public UploadReport uploadQuestions(MultipartFile file, Long examId) {
        UploadReport report = new UploadReport();

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }

        Exam exam = examRepository.findById(examId).orElse(null);
        Map<String, Long> sectionsByName = new HashMap<>();
        for (Section s : sectionRepository.findByExamId(examId)) {
            if (s.getName() != null) sectionsByName.put(s.getName().trim().toLowerCase(), s.getId());
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                List<String> f = CsvParser.parseLine(line);

                if (CsvParser.isBlank(f)) continue;
                if (lineNumber == 1 && CsvParser.looksLikeHeader(f)) continue;

                if (f.size() < 6) {
                    report.recordError(lineNumber,
                            "Expected at least 6 columns, found " + f.size() + ".", line);
                    continue;
                }

                try {
                    Question q = new Question();
                    q.setExamId(examId);
                    q.setQuestionText(f.get(0));
                    q.setOptionA(f.get(1));
                    q.setOptionB(f.get(2));
                    q.setOptionC(f.get(3));
                    q.setOptionD(f.get(4));
                    q.setCorrectAnswer(f.get(5));

                    if (f.size() > 6 && !f.get(6).isBlank()) q.setMarks(Integer.parseInt(f.get(6)));
                    if (f.size() > 7 && !f.get(7).isBlank()) q.setNegativeMarks(Double.parseDouble(f.get(7)));

                    if (f.size() > 8 && !f.get(8).isBlank()) {
                        q.setSectionId(resolveSection(examId, f.get(8), sectionsByName));
                    }

                    applyExamDefaults(q, exam);
                    validate(q);
                    questionRepository.save(q);
                    report.recordSaved();

                } catch (NumberFormatException e) {
                    report.recordError(lineNumber, "Marks must be numeric.", line);
                } catch (IllegalArgumentException e) {
                    report.recordError(lineNumber, e.getMessage(), line);
                }
            }

        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read the file: " + e.getMessage());
        }

        return report;
    }

    /**
     * Saves questions an admin reviewed on screen after a document import.
     *
     * Runs the same validation as every other route in. A question that came
     * from a PDF gets no more trust than one typed by hand — less, if anything,
     * which is exactly why it passed through a human first.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public UploadReport importReviewed(Long examId, Object rawQuestions) {
        UploadReport report = new UploadReport();

        if (!(rawQuestions instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalArgumentException("No questions were submitted.");
        }

        Exam exam = examRepository.findById(examId).orElse(null);
        Map<String, Long> sectionsByName = new HashMap<>();
        for (Section s : sectionRepository.findByExamId(examId)) {
            if (s.getName() != null) sectionsByName.put(s.getName().trim().toLowerCase(), s.getId());
        }

        int line = 0;
        for (Object row : rows) {
            line++;
            if (!(row instanceof Map<?, ?> map)) {
                report.recordError(line, "Malformed question.", "");
                continue;
            }
            Map<String, Object> q = (Map<String, Object>) map;

            try {
                Question question = new Question();
                question.setExamId(examId);
                question.setQuestionText(str(q.get("questionText")));
                question.setOptionA(str(q.get("optionA")));
                question.setOptionB(str(q.get("optionB")));
                question.setOptionC(str(q.get("optionC")));
                question.setOptionD(str(q.get("optionD")));
                question.setCorrectAnswer(str(q.get("correctAnswer")));
                // The review screen sends back `images` (the list the parser
                // produced, which a reviewer may have edited); a single
                // `questionImage` only appears if something set it explicitly.
                // Reading only the latter meant every extracted diagram was
                // shown in the preview and then silently dropped on save — the
                // import appeared to handle figures and actually discarded them.
                String image = str(q.get("questionImage"));
                if (image == null || image.isBlank()) {
                    Object raw = q.get("images");
                    if (raw instanceof List<?> list && !list.isEmpty()) {
                        image = str(list.get(0));
                    }
                }
                question.setQuestionImage(image);

                if (q.get("marks") != null && !str(q.get("marks")).isBlank()) {
                    question.setMarks(Integer.parseInt(str(q.get("marks"))));
                }
                if (q.get("negativeMarks") != null && !str(q.get("negativeMarks")).isBlank()) {
                    question.setNegativeMarks(Double.parseDouble(str(q.get("negativeMarks"))));
                }

                String sectionName = str(q.get("sectionName"));
                if (sectionName != null && !sectionName.isBlank()) {
                    question.setSectionId(resolveSection(examId, sectionName, sectionsByName));
                }

                applyExamDefaults(question, exam);
                validate(question);
                questionRepository.save(question);
                report.recordSaved();

            } catch (NumberFormatException e) {
                report.recordError(line, "Marks must be numeric.", str(q.get("questionText")));
            } catch (IllegalArgumentException e) {
                report.recordError(line, e.getMessage(), str(q.get("questionText")));
            }
        }
        return report;
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    /** Creates sections named in the CSV on the fly, so one file can seed a full paper. */
    private Long resolveSection(Long examId, String name, Map<String, Long> cache) {
        String key = name.trim().toLowerCase();
        Long existing = cache.get(key);
        if (existing != null) return existing;

        Section section = new Section();
        section.setExamId(examId);
        section.setName(name.trim());
        Section saved = sectionRepository.save(section);
        cache.put(key, saved.getId());
        return saved.getId();
    }
}
