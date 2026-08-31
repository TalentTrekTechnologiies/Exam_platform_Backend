package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Admin;
import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.dto.PublicationStatus;
import com.Exam.Exam_System.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deciding whether an exam may be sat, and producing the link candidates use.
 *
 * Publishing is a separate, deliberate act from creating. An exam is built over
 * days — questions added, a key corrected, candidates enrolled — and during all
 * of that it must not be sittable. Publishing is the moment an exam officer says
 * "this is finished", and it is checked rather than taken on trust.
 */
@Service
public class PublicationService {

    private static final Logger log = LoggerFactory.getLogger(PublicationService.class);

    /**
     * Public origin candidates reach the exam on, e.g. https://exams.ksrm.edu.in.
     * Needed because the server cannot otherwise know the address the outside
     * world uses to reach it — it may sit behind a proxy, a different hostname,
     * or a different port entirely.
     */
    @Value("${app.candidate-base-url:}")
    private String candidateBaseUrl;

    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final SectionRepository sectionRepository;
    private final SlotRepository slotRepository;
    private final ExamStudentRepository examStudentRepository;
    private final AttemptRepository attemptRepository;
    private final AdminRepository adminRepository;
    private final TestCaseRepository testCaseRepository;
    private final com.Exam.Exam_System.service.judge.Judge judge;

    public PublicationService(ExamRepository examRepository,
                              QuestionRepository questionRepository,
                              SectionRepository sectionRepository,
                              SlotRepository slotRepository,
                              ExamStudentRepository examStudentRepository,
                              AttemptRepository attemptRepository,
                              AdminRepository adminRepository,
                              TestCaseRepository testCaseRepository,
                              com.Exam.Exam_System.service.judge.Judge judge) {
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.sectionRepository = sectionRepository;
        this.slotRepository = slotRepository;
        this.examStudentRepository = examStudentRepository;
        this.attemptRepository = attemptRepository;
        this.adminRepository = adminRepository;
        this.testCaseRepository = testCaseRepository;
        this.judge = judge;
    }

    /** Full readiness picture: counts, blockers, warnings, and the candidate link. */
    @Transactional(readOnly = true)
    public PublicationStatus status(Exam exam) {
        List<Question> questions = questionRepository.findByExamId(exam.getId());
        int sections = sectionRepository.findByExamId(exam.getId()).size();
        int slots = slotRepository.findAllByExamId(exam.getId()).size();
        int candidates = examStudentRepository.findByExamId(exam.getId()).size();

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        /*
         * Counts describe the paper a candidate sits, not the bank it is drawn
         * from. A college holding two hundred Physics questions and setting
         * twenty per paper needs this screen to say twenty, or the marks shown
         * here are not the marks anybody can score.
         */
        Map<Long, List<Question>> bySection = new LinkedHashMap<>();
        for (Question q : questions) {
            bySection.computeIfAbsent(q.getSectionId(), k -> new ArrayList<>()).add(q);
        }

        Map<Long, Integer> draws = new HashMap<>();
        for (Section s : sectionRepository.findByExamId(exam.getId())) {
            List<Question> bank = bySection.getOrDefault(s.getId(), List.of());
            Integer draw = s.getQuestionsToDraw();
            if (draw == null || draw <= 0) continue;

            if (draw > bank.size()) {
                // Freezing takes the whole bank rather than failing the paper,
                // so this is worth saying out loud but must not block a sitting.
                warnings.add("\"" + s.getName() + "\" is set to draw " + draw
                        + " questions but only has " + bank.size() + ". Every candidate will get all "
                        + bank.size() + ".");
                continue;
            }

            draws.put(s.getId(), draw);

            // Marks have to match inside a drawn section, or two candidates sit
            // papers worth different totals and their scores stop being
            // comparable — which a rank order hides rather than shows.
            if (draw < bank.size()
                    && bank.stream().map(q -> q.getMarks() == null ? 1 : q.getMarks()).distinct().count() > 1) {
                warnings.add("\"" + s.getName() + "\" draws " + draw + " of " + bank.size()
                        + " questions, but they are not all worth the same marks — candidates would"
                        + " sit papers with different totals. Even the marks up, or draw every question.");
            }
        }

        // The paper as it will actually be sat. Where a section's marks are
        // even, any n of them come to the same total; where they are not, the
        // warning above already says this number cannot be relied on.
        double totalMarks = 0;
        int paperQuestions = 0;
        for (Map.Entry<Long, List<Question>> e : bySection.entrySet()) {
            List<Question> bank = e.getValue();
            Integer draw = e.getKey() == null ? null : draws.get(e.getKey());
            int take = draw == null ? bank.size() : Math.min(draw, bank.size());
            for (int i = 0; i < take; i++) {
                totalMarks += bank.get(i).getMarks() == null ? 1 : bank.get(i).getMarks();
            }
            paperQuestions += take;
        }

        if (questions.isEmpty()) blockers.add("This exam has no questions.");
        if (slots == 0) blockers.add("No exam slot has been scheduled, so there is no window in which anyone can sit it.");
        if (exam.getDuration() == null || exam.getDuration() <= 0) blockers.add("The exam has no duration set.");

        // A question whose keyed option is blank cannot be marked, and would
        // silently cost every candidate the same marks.
        long unmarkable = questions.stream().filter(this::isUnmarkable).count();
        if (unmarkable > 0) {
            blockers.add(unmarkable + " question(s) could not be marked — a multiple-choice question whose keyed option is blank, or a coding question with no test cases.");
        }

        /*
         * A coding paper with nowhere to run code.
         *
         * A blocker rather than a warning, because the failure lands on the
         * candidate and lands late: they sit down, read the problem, write a
         * solution, press Run, and are told the judge is unavailable. There is
         * no recovering that mid-sitting, and the exam officer publishing the
         * paper is the last person able to prevent it.
         */
        long codingCount = questions.stream().filter(Question::isCoding).count();
        if (codingCount > 0 && !judge.available()) {
            blockers.add(codingCount + " coding question(s) are on this paper, but no code judge is"
                    + " configured — candidates could not run or submit a program. Deploy Judge0"
                    + " (deploy/docker-compose.judge.yml) and set judge.provider=judge0.");
        }

        if (candidates == 0) {
            warnings.add("No candidates are enrolled yet. You can publish now and enrol them later.");
        }

        // Slots shorter than the paper quietly truncate everyone's time.
        slotRepository.findAllByExamId(exam.getId()).forEach(slot -> {
            if (slot.getStartTime() == null || slot.getEndTime() == null) return;
            long windowMinutes = java.time.Duration.between(slot.getStartTime(), slot.getEndTime()).toMinutes();
            if (exam.getDuration() != null && windowMinutes < exam.getDuration()) {
                warnings.add("A slot is only " + windowMinutes + " minutes long but the paper is "
                        + exam.getDuration() + " minutes — candidates' time will be cut short.");
            }
        });

        int prepared = (int) attemptRepository.countByExamId(exam.getId());
        if (candidates > 0 && prepared < candidates) {
            warnings.add((candidates - prepared) + " candidate(s) have no paper prepared yet. "
                    + "Run Prepare Papers before exam day for a faster, calmer start.");
        }

        PublicationStatus status = new PublicationStatus();
        status.setPublished(exam.isPublished());
        status.setPublishedAt(exam.getPublishedAt());
        // What a candidate sits, with the bank behind it when they differ.
        status.setQuestionCount(paperQuestions);
        status.setQuestionBank(paperQuestions == questions.size() ? 0 : questions.size());
        status.setSectionCount(sections);
        status.setSlotCount(slots);
        status.setCandidateCount(candidates);
        status.setPreparedPapers(prepared);
        status.setTotalMarks(totalMarks);
        status.setBlockers(blockers);
        status.setWarnings(warnings);
        status.setCandidateLink(candidateLinkFor(exam));
        return status;
    }

    /**
     * The address to hand candidates.
     *
     * Institution-scoped, because each college has its own entrance — that is
     * what lets two colleges use the same roll numbers without colliding.
     */
    private String candidateLinkFor(Exam exam) {
        if (candidateBaseUrl == null || candidateBaseUrl.isBlank()) return null;
        String base = candidateBaseUrl.replaceAll("/+$", "");

        Admin owner = exam.getAdminId() == null ? null
                : adminRepository.findById(exam.getAdminId()).orElse(null);

        // A dedicated single-institution install has no code in its URLs.
        if (owner == null || owner.getCode() == null || owner.getCode().isBlank()) {
            return base + "/verify";
        }
        return base + "/verify?college=" + owner.getCode();
    }

    /**
     * Whether a question would go in front of candidates unable to be marked.
     *
     * Split by type, because the two fail in different ways. A multiple-choice
     * question is unmarkable when its keyed option is blank. A coding question
     * has no key at all — it is unmarkable when it has no test cases, and
     * running the old option check against one reported every coding question
     * as broken and made publishing the exam impossible.
     */
    private boolean isUnmarkable(Question q) {
        if (q.isCoding()) return testCaseRepository.countByQuestionId(q.getId()) == 0;

        String correct = q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer().trim().toUpperCase();
        String text = switch (correct) {
            case "A" -> q.getOptionA();
            case "B" -> q.getOptionB();
            case "C" -> q.getOptionC();
            case "D" -> q.getOptionD();
            default -> null;
        };
        String image = switch (correct) {
            case "A" -> q.getOptionAImage();
            case "B" -> q.getOptionBImage();
            case "C" -> q.getOptionCImage();
            case "D" -> q.getOptionDImage();
            default -> null;
        };
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isBlank();
        return !(hasText || hasImage);
    }

    /** Opens the exam to candidates. Refuses while any blocker stands. */
    @Transactional
    public PublicationStatus publish(Exam exam) {
        PublicationStatus status = status(exam);

        if (!status.isReady()) {
            throw new IllegalStateException("NOT_READY_TO_PUBLISH: " + String.join(" ", status.getBlockers()));
        }
        if (exam.isPublished()) return status;

        exam.setPublished(true);
        exam.setPublishedAt(LocalDateTime.now());
        examRepository.save(exam);
        log.info("Exam {} published.", exam.getId());

        return status(exam);
    }

    /**
     * Closes the exam again.
     *
     * Deliberately refuses once anyone has started: withdrawing a paper from
     * under a candidate mid-sitting is never the right answer to anything.
     */
    @Transactional
    public PublicationStatus unpublish(Exam exam) {
        long started = attemptRepository.countByExamIdAndStatusNot(exam.getId(), "PENDING");
        if (started > 0) {
            throw new IllegalStateException(
                    "CANNOT_UNPUBLISH_IN_PROGRESS: " + started + " candidate(s) have already begun this exam.");
        }

        exam.setPublished(false);
        exam.setPublishedAt(null);
        examRepository.save(exam);
        log.info("Exam {} unpublished.", exam.getId());

        return status(exam);
    }
}
