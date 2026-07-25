package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.AttemptQuestion;
import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.dto.PaperQuestionResponse;
import com.Exam.Exam_System.repository.AttemptQuestionRepository;
import com.Exam.Exam_System.repository.QuestionRepository;
import com.Exam.Exam_System.repository.SectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Owns the paper a candidate sees: which questions, in what order, with options
 * in what order. The layout is frozen at attempt start and replayed from the DB
 * thereafter.
 */
@Service
public class PaperService {

    private static final Logger log = LoggerFactory.getLogger(PaperService.class);

    /** Canonical option letters, in their authored order. */
    private static final List<String> LETTERS = List.of("A", "B", "C", "D");

    private final QuestionRepository questionRepository;
    private final SectionRepository sectionRepository;
    private final AttemptQuestionRepository attemptQuestionRepository;
    private final JdbcTemplate jdbcTemplate;

    public PaperService(QuestionRepository questionRepository,
                        SectionRepository sectionRepository,
                        AttemptQuestionRepository attemptQuestionRepository,
                        JdbcTemplate jdbcTemplate) {
        this.questionRepository = questionRepository;
        this.sectionRepository = sectionRepository;
        this.attemptQuestionRepository = attemptQuestionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** An option in its canonical form, before any shuffling. */
    private record CanonicalOption(String letter, String text, String image) {}

    private List<CanonicalOption> canonicalOptions(Question q) {
        List<CanonicalOption> raw = List.of(
                new CanonicalOption("A", q.getOptionA(), q.getOptionAImage()),
                new CanonicalOption("B", q.getOptionB(), q.getOptionBImage()),
                new CanonicalOption("C", q.getOptionC(), q.getOptionCImage()),
                new CanonicalOption("D", q.getOptionD(), q.getOptionDImage())
        );
        // An option counts as present if it has text or an image — image-only
        // options are normal in maths and diagram questions.
        List<CanonicalOption> present = new ArrayList<>();
        for (CanonicalOption o : raw) {
            boolean hasText = o.text() != null && !o.text().isBlank();
            boolean hasImage = o.image() != null && !o.image().isBlank();
            if (hasText || hasImage) present.add(o);
        }
        return present;
    }

    /**
     * Freezes the paper layout for a new attempt. Idempotent: if a layout already
     * exists for this attempt it is left untouched, so a double-tap on "Start"
     * can never hand the candidate a different paper.
     */
    @Transactional
    public void buildPaper(Long attemptId, Long examId) {
        if (attemptQuestionRepository.existsByAttemptId(attemptId)) {
            return;
        }

        List<Question> questions = new ArrayList<>(questionRepository.findByExamId(examId));
        if (questions.isEmpty()) {
            throw new IllegalStateException("This exam has no questions yet.");
        }

        // A question with no usable options is unanswerable — leaving it in the
        // paper would count against the candidate's max score for nothing.
        questions.removeIf(q -> {
            boolean empty = canonicalOptions(q).isEmpty();
            if (empty) log.warn("Question {} has no options; excluded from exam {} paper.", q.getId(), examId);
            return empty;
        });

        if (questions.isEmpty()) {
            throw new IllegalStateException("This exam has no answerable questions.");
        }

        // Keep sections contiguous: shuffle questions within a section, and shuffle
        // the section blocks, but never interleave them. A candidate jumping between
        // Physics and Chemistry mid-paper is not how these exams work.
        Map<Long, List<Question>> bySection = new LinkedHashMap<>();
        for (Question q : questions) {
            bySection.computeIfAbsent(q.getSectionId(), k -> new ArrayList<>()).add(q);
        }

        List<Long> sectionIds = new ArrayList<>(bySection.keySet());
        sectionIds.sort(Comparator.comparing(id -> id == null ? Long.MAX_VALUE : id));

        // Build the rows as plain tuples, then insert them in one JDBC batch.
        // AttemptQuestion uses IDENTITY id generation, which Hibernate cannot
        // batch — saveAll() would fire one INSERT per row. At slot-open, when
        // thousands of candidates freeze ~180-row papers within seconds, that
        // per-row chatter is the burst that hurts. A single batched statement
        // collapses each paper to essentially one round trip.
        List<Object[]> rows = new ArrayList<>();
        int order = 1;
        for (Long sectionId : sectionIds) {
            List<Question> block = bySection.get(sectionId);
            Collections.shuffle(block);
            for (Question q : block) {
                List<String> letters = new ArrayList<>();
                for (CanonicalOption o : canonicalOptions(q)) letters.add(o.letter());
                Collections.shuffle(letters);
                rows.add(new Object[]{ attemptId, q.getId(), order++, String.join(",", letters) });
            }
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO attempt_questions (attempt_id, question_id, display_order, option_order) "
                        + "VALUES (?, ?, ?, ?)",
                rows);

        log.info("Froze paper for attempt {} — {} questions across {} section(s).",
                attemptId, rows.size(), sectionIds.size());
    }

    /** Replays the frozen layout. Never includes the correct answer. */
    @Transactional(readOnly = true)
    public List<PaperQuestionResponse> getPaper(Long attemptId) {
        List<AttemptQuestion> layout = attemptQuestionRepository.findByAttemptIdOrderByDisplayOrderAsc(attemptId);
        if (layout.isEmpty()) {
            throw new IllegalStateException("No paper found for this attempt.");
        }

        Map<Long, Question> questions = new HashMap<>();
        for (Question q : questionRepository.findAllById(
                layout.stream().map(AttemptQuestion::getQuestionId).toList())) {
            questions.put(q.getId(), q);
        }

        Map<Long, String> sectionNames = sectionNamesFor(questions.values());

        List<PaperQuestionResponse> paper = new ArrayList<>();
        for (AttemptQuestion aq : layout) {
            Question q = questions.get(aq.getQuestionId());
            if (q == null) continue; // question deleted after the attempt started

            paper.add(new PaperQuestionResponse(
                    q.getId(),
                    aq.getDisplayOrder(),
                    q.getSectionId(),
                    sectionNames.get(q.getSectionId()),
                    q.getQuestionText(),
                    q.getQuestionImage(),
                    q.getMarks() == null ? 1 : q.getMarks(),
                    q.getNegativeMarks() == null ? 0.0 : q.getNegativeMarks(),
                    optionsInDisplayOrder(q, aq.getOptionOrder())
            ));
        }
        return paper;
    }

    /** Applies a stored permutation to a question's options. */
    public List<PaperQuestionResponse.OptionView> optionsInDisplayOrder(Question q, String optionOrder) {
        Map<String, CanonicalOption> byLetter = new HashMap<>();
        for (CanonicalOption o : canonicalOptions(q)) byLetter.put(o.letter(), o);

        List<PaperQuestionResponse.OptionView> views = new ArrayList<>();
        for (String letter : optionOrder.split(",")) {
            CanonicalOption o = byLetter.remove(letter.trim());
            if (o != null) views.add(new PaperQuestionResponse.OptionView(o.letter(), o.text(), o.image()));
        }
        // Any option added to the question after this attempt started still gets
        // shown, appended in canonical order, rather than silently vanishing.
        for (String letter : LETTERS) {
            CanonicalOption o = byLetter.get(letter);
            if (o != null) views.add(new PaperQuestionResponse.OptionView(o.letter(), o.text(), o.image()));
        }
        return views;
    }

    /**
     * Describes the paper without revealing it: section names, question counts
     * and the marking scheme. Used by the pre-exam briefing so a candidate knows
     * what they are walking into, the way a printed question paper's front page
     * would tell them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> describeStructure(Long examId) {
        // Copy before mutating — the repository's list is not ours to modify.
        List<Question> questions = new ArrayList<>(questionRepository.findByExamId(examId));
        questions.removeIf(q -> canonicalOptions(q).isEmpty());

        Map<Long, String> sectionNames = new LinkedHashMap<>();
        for (Section s : sectionRepository.findByExamId(examId)) {
            sectionNames.put(s.getId(), s.getName());
        }

        // Preserve authoring order, with any unsectioned questions grouped last.
        Map<Long, List<Question>> grouped = new LinkedHashMap<>();
        for (Long sectionId : sectionNames.keySet()) grouped.put(sectionId, new ArrayList<>());
        for (Question q : questions) {
            grouped.computeIfAbsent(q.getSectionId(), k -> new ArrayList<>()).add(q);
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        int totalQuestions = 0;
        double totalMarks = 0;
        Set<Integer> awards = new LinkedHashSet<>();
        Set<Double> penalties = new LinkedHashSet<>();

        for (Map.Entry<Long, List<Question>> entry : grouped.entrySet()) {
            List<Question> block = entry.getValue();
            if (block.isEmpty()) continue;

            double blockMarks = 0;
            for (Question q : block) {
                int marks = q.getMarks() == null ? 1 : q.getMarks();
                double penalty = q.getNegativeMarks() == null ? 0.0 : Math.abs(q.getNegativeMarks());
                blockMarks += marks;
                awards.add(marks);
                penalties.add(penalty);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", sectionNames.getOrDefault(entry.getKey(), "General"));
            row.put("questionCount", block.size());
            row.put("marks", blockMarks);
            sections.add(row);

            totalQuestions += block.size();
            totalMarks += blockMarks;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", sections);
        out.put("totalQuestions", totalQuestions);
        out.put("totalMarks", totalMarks);
        // Only advertise a single scheme when the paper actually uses one; a mixed
        // paper must not tell the candidate something untrue about the marking.
        out.put("marksPerQuestion", awards.size() == 1 ? awards.iterator().next() : null);
        out.put("negativePerQuestion", penalties.size() == 1 ? penalties.iterator().next() : null);
        return out;
    }

    public Map<Long, String> sectionNamesFor(Collection<Question> questions) {
        Set<Long> examIds = new HashSet<>();
        for (Question q : questions) if (q.getExamId() != null) examIds.add(q.getExamId());

        Map<Long, String> names = new HashMap<>();
        for (Long examId : examIds) {
            for (Section s : sectionRepository.findByExamId(examId)) {
                names.put(s.getId(), s.getName());
            }
        }
        return names;
    }
}
