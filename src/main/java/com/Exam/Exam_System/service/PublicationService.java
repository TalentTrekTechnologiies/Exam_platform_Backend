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
    private final AttemptService attemptService;

    public PublicationService(ExamRepository examRepository,
                              QuestionRepository questionRepository,
                              SectionRepository sectionRepository,
                              SlotRepository slotRepository,
                              ExamStudentRepository examStudentRepository,
                              AttemptRepository attemptRepository,
                              AdminRepository adminRepository,
                              TestCaseRepository testCaseRepository,
                              com.Exam.Exam_System.service.judge.Judge judge,
                              @org.springframework.context.annotation.Lazy AttemptService attemptService) {
        this.attemptService = attemptService;
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

        /*
         * A half-specified sectional paper.
         *
         * Sectional timing is all or nothing: it needs a duration on EVERY
         * section, because a section without one has no boundary to close at.
         * Setting it on two of three reads as a sectional exam and behaves as
         * an ordinary one, which is the worst of both — an exam officer who
         * believes the coding section is locked and finds it was not.
         */
        List<Section> examSections = sectionRepository.findByExamId(exam.getId());
        long timed = examSections.stream()
                .filter(x -> x.getDurationMinutes() != null && x.getDurationMinutes() > 0).count();
        if (timed > 0 && timed < examSections.size()) {
            blockers.add((examSections.size() - timed) + " section(s) have no time limit while "
                    + timed + " do. Sectional timing needs a limit on every section, or none —"
                    + " as it stands the sections would not lock at all.");
        }

        if (timed > 0 && timed == examSections.size() && exam.getDuration() != null) {
            int sectionTotal = examSections.stream()
                    .mapToInt(Section::getDurationMinutes).sum();
            if (sectionTotal > exam.getDuration()) {
                warnings.add("The sections add up to " + sectionTotal + " minutes but the paper is "
                        + exam.getDuration() + ". The last section will be cut short when the"
                        + " paper's own clock runs out.");
            } else if (sectionTotal < exam.getDuration()) {
                warnings.add("The sections add up to " + sectionTotal + " minutes and the paper is "
                        + exam.getDuration() + ", so candidates finish " + (exam.getDuration() - sectionTotal)
                        + " minutes early. Set the paper's duration to match if that is not intended.");
            }
        }

        if (candidates == 0) {
            warnings.add("No candidates are enrolled yet. You can publish now and enrol them later.");
        }

        // A slot's end is the last moment to START: everyone gets the full
        // duration from when they begin, so a short slot cuts nobody short.
        // (This used to warn that a 5-minute slot would truncate a 3-hour
        // paper, which stopped being true when late starters got full time.)
        // What is worth saying is late entry left open as long as the paper:
        // someone starting at the end of it can meet candidates who have
        // already finished, and hear the questions before they answer them.
        slotRepository.findAllByExamId(exam.getId()).forEach(slot -> {
            if (slot.getStartTime() == null || slot.getEndTime() == null) return;
            long lateEntry = java.time.Duration.between(slot.getStartTime(), slot.getEndTime()).toMinutes();
            if (exam.getDuration() != null && exam.getDuration() > 0 && lateEntry >= exam.getDuration()) {
                warnings.add("A sitting lets candidates start up to " + lateEntry + " minutes late, as long as the "
                        + exam.getDuration() + "-minute paper itself. A late starter can meet candidates who have "
                        + "already finished. Shorten its buffer on the Slots page if that is not intended.");
            }
        });

        // Before publishing there is nothing to say: publishing prepares them.
        // After, only candidates enrolled since then can be without one.
        int prepared = (int) attemptRepository.countByExamId(exam.getId());
        if (exam.isPublished() && candidates > 0 && prepared < candidates) {
            warnings.add((candidates - prepared) + " candidate(s) do not have a paper ready yet. The server "
                    + "prepares them automatically within a couple of minutes -- there is nothing to do, and "
                    + "anyone who starts before then gets theirs at Start.");
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

        // Every candidate's paper is built as part of publishing, rather than
        // left as a separate "Prepare Papers" step that staff were told to run
        // after importing the questions -- and that, skipped, left the server
        // building hundreds of papers in the second the exam opened.
        //
        // In the background: 600 candidates on a 300-question paper is 180,000
        // rows and about a minute, longer than the admin screen or nginx will
        // wait for an answer. Publishing returns at once; "Papers ready" on the
        // Publish page fills in as it goes. Never the reason a publish fails: a
        // paper not built here is built when that candidate presses Start.
        Long examId = exam.getId();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                int made = attemptService.prepareMissing(examId);
                log.info("Exam {} published; {} paper(s) prepared.", examId, made);
            } catch (RuntimeException e) {
                log.warn("Exam {} published, but preparing papers failed: {}", examId, e.toString());
            }
        });

        return status(exam);
    }

    /**
     * Closes the exam again.
     *
     * Refuses only while somebody is writing: withdrawing a paper from under a
     * candidate mid-sitting is never the right answer to anything.
     *
     * It used to refuse once anyone had started at all, finished or not, so an
     * exam a single candidate had submitted could never be unpublished -- while
     * the delete screen told the admin to unpublish it instead. Submitted
     * results stay exactly where they are; unpublishing only stops new sign-ins.
     */
    @Transactional
    public PublicationStatus unpublish(Exam exam) {
        long writing = attemptRepository.countWritingNow(exam.getId());
        if (writing > 0) {
            throw new IllegalStateException(
                    "CANNOT_UNPUBLISH_IN_PROGRESS: " + writing + " candidate(s) are writing this exam right now. "
                            + "Unpublish it once they have submitted.");
        }

        exam.setPublished(false);
        exam.setPublishedAt(null);
        examRepository.save(exam);
        log.info("Exam {} unpublished.", exam.getId());

        return status(exam);
    }
}
