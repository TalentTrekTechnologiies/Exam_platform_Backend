package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.*;
import com.Exam.Exam_System.dto.ResultResponse;
import com.Exam.Exam_System.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Grading. Previously this did not exist in any working form: answers were never
 * marked correct, so every candidate scored 0.0 regardless of what they wrote.
 *
 * Marking scheme is per-question, which is what lets one platform run EAMCET
 * (no negative), NEET/JEE (-1), and TCS NQT (0) side by side.
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final AttemptRepository attemptRepository;
    private final AnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final AttemptQuestionRepository attemptQuestionRepository;
    private final StudentRepository studentRepository;
    private final ExamRepository examRepository;
    private final PaperService paperService;
    private final AttemptCache attemptCache;
    private final RankingService rankingService;

    public ScoringService(AttemptRepository attemptRepository,
                          AnswerRepository answerRepository,
                          QuestionRepository questionRepository,
                          AttemptQuestionRepository attemptQuestionRepository,
                          StudentRepository studentRepository,
                          ExamRepository examRepository,
                          PaperService paperService,
                          AttemptCache attemptCache,
                          RankingService rankingService) {
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.attemptQuestionRepository = attemptQuestionRepository;
        this.studentRepository = studentRepository;
        this.examRepository = examRepository;
        this.paperService = paperService;
        this.attemptCache = attemptCache;
        this.rankingService = rankingService;
    }

    private static int marksOf(Question q) {
        return q.getMarks() == null ? 1 : q.getMarks();
    }

    private static double penaltyOf(Question q) {
        return q.getNegativeMarks() == null ? 0.0 : Math.abs(q.getNegativeMarks());
    }

    /**
     * Grades and closes an attempt. Idempotent — a candidate hammering Submit, or
     * a submit racing the auto-submit timer, grades once and returns the same score.
     */
    @Transactional
    public Attempt submit(Long attemptId, String reason) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));

        if ("SUBMITTED".equals(attempt.getStatus())) {
            return attempt;
        }

        List<AttemptQuestion> layout = attemptQuestionRepository.findByAttemptIdOrderByDisplayOrderAsc(attemptId);
        Map<Long, Question> questions = loadQuestions(layout);
        Map<Long, Answer> answers = answersByQuestion(attemptId);

        double score = 0.0;
        List<Answer> toPersist = new ArrayList<>();

        for (AttemptQuestion aq : layout) {
            Question q = questions.get(aq.getQuestionId());
            if (q == null) continue;

            Answer a = answers.get(aq.getQuestionId());
            if (a == null) continue;   // unanswered scores zero, never a penalty

            // A coding answer was already marked when the candidate submitted
            // it — every test case was run then, deliberately, so that five
            // thousand programs are not all compiled at the final whistle. Its
            // marks are read back rather than recalculated, and it is never
            // put through the multiple-choice comparison below: there is no
            // correct letter to compare against, and doing so would apply the
            // negative marking to every coding answer in the paper.
            if (q.isCoding()) {
                if (a.getAwardedMarks() == null) continue;
                score += a.getAwardedMarks();
                continue;
            }

            if (a.getSelectedOption() == null || a.getSelectedOption().isBlank()) {
                continue;
            }

            boolean correct = a.getSelectedOption().trim().equalsIgnoreCase(
                    q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer().trim());

            a.setIsCorrect(correct);
            toPersist.add(a);
            score += correct ? marksOf(q) : -penaltyOf(q);
        }

        answerRepository.saveAll(toPersist);

        LocalDateTime finishedAt = LocalDateTime.now();
        attempt.setStatus("SUBMITTED");
        attempt.setScore(score);
        // endTime stays the deadline; submittedAt records when they actually
        // finished. Keeping them separate is what lets the clock be cached.
        attempt.setSubmittedAt(finishedAt);
        Attempt saved = attemptRepository.save(attempt);

        // Clock polls now compute zero remaining straight from memory.
        attemptCache.markEnded(attemptId, finishedAt);

        log.info("Graded attempt {} — score {} ({})", attemptId, score, reason);
        return saved;
    }

    /**
     * Builds the scorecard. Refuses to build one for a live attempt: the review
     * section contains the answer key, and handing that to a candidate mid-exam
     * would recreate the leak this rewrite exists to close.
     */
    @Transactional(readOnly = true)
    public ResultResponse buildResult(Long attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));

        if (!"SUBMITTED".equals(attempt.getStatus())) {
            throw new IllegalStateException("RESULT_NOT_READY");
        }

        List<AttemptQuestion> layout = attemptQuestionRepository.findByAttemptIdOrderByDisplayOrderAsc(attemptId);
        Map<Long, Question> questions = loadQuestions(layout);
        Map<Long, Answer> answers = answersByQuestion(attemptId);
        Map<Long, String> sectionNames = paperService.sectionNamesFor(questions.values());

        ResultResponse out = new ResultResponse();
        List<ResultResponse.ReviewQuestion> review = new ArrayList<>();

        // sectionId -> running tallies
        Map<Long, int[]> sectionCounts = new LinkedHashMap<>();   // [correct, incorrect, unanswered]
        Map<Long, double[]> sectionScores = new LinkedHashMap<>(); // [score, maxScore]

        int correct = 0, incorrect = 0, unanswered = 0;
        double score = 0.0, maxScore = 0.0;

        for (AttemptQuestion aq : layout) {
            Question q = questions.get(aq.getQuestionId());
            if (q == null) continue;

            Long sid = q.getSectionId();
            sectionCounts.computeIfAbsent(sid, k -> new int[3]);
            sectionScores.computeIfAbsent(sid, k -> new double[2]);

            Answer a = answers.get(aq.getQuestionId());
            boolean coding = q.isCoding();

            String given;
            boolean isCorrect;
            double awarded;

            if (coding) {
                // "Attempted" means code was submitted and judged, and correct
                // means every case passed. A partial pass is neither — it is
                // shown by the marks it earned, which is the only honest way to
                // report seven cases of ten.
                boolean judged = a != null && a.getAwardedMarks() != null;
                given = judged ? (a.getLanguage() == null ? "code" : a.getLanguage()) : null;
                isCorrect = judged && a.getTestsTotal() != null && a.getTestsTotal() > 0
                        && Objects.equals(a.getTestsPassed(), a.getTestsTotal());
                awarded = judged ? a.getAwardedMarks() : 0.0;
            } else {
                given = (a == null || a.getSelectedOption() == null || a.getSelectedOption().isBlank())
                        ? null : a.getSelectedOption().trim();
                isCorrect = given != null && given.equalsIgnoreCase(
                        q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer().trim());
                awarded = given == null ? 0.0 : (isCorrect ? marksOf(q) : -penaltyOf(q));
            }

            if (given == null) {
                unanswered++;
                sectionCounts.get(sid)[2]++;
            } else if (isCorrect) {
                correct++;
                sectionCounts.get(sid)[0]++;
            } else {
                incorrect++;
                sectionCounts.get(sid)[1]++;
            }

            score += awarded;
            maxScore += marksOf(q);
            sectionScores.get(sid)[0] += awarded;
            sectionScores.get(sid)[1] += marksOf(q);

            review.add(new ResultResponse.ReviewQuestion(
                    q.getId(),
                    aq.getDisplayOrder(),
                    sectionNames.get(sid),
                    q.getQuestionText(),
                    q.getQuestionImage(),
                    paperService.optionsInDisplayOrder(q, aq.getOptionOrder()),
                    q.getCorrectAnswer(),
                    given,
                    isCorrect,
                    given != null,
                    awarded
            ));
        }

        List<ResultResponse.SectionScore> sections = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : sectionCounts.entrySet()) {
            Long sid = e.getKey();
            int[] c = e.getValue();
            double[] s = sectionScores.get(sid);
            sections.add(new ResultResponse.SectionScore(
                    sid,
                    sectionNames.getOrDefault(sid, "General"),
                    c[0], c[1], c[2], s[0], s[1]
            ));
        }

        out.setAttemptId(attempt.getId());
        out.setStatus(attempt.getStatus());
        out.setScore(score);
        out.setMaxScore(maxScore);
        out.setPercentage(maxScore > 0 ? Math.max(0, score) / maxScore * 100.0 : 0.0);
        out.setCorrect(correct);
        out.setIncorrect(incorrect);
        out.setUnanswered(unanswered);
        out.setTotal(correct + incorrect + unanswered);
        out.setStartTime(attempt.getStartTime());
        // The scorecard shows when they finished, not when the clock would have run out.
        out.setEndTime(attempt.getSubmittedAt() != null ? attempt.getSubmittedAt() : attempt.getEndTime());
        out.setSections(sections);
        out.setQuestions(review);

        LocalDateTime finished = attempt.getSubmittedAt() != null ? attempt.getSubmittedAt() : attempt.getEndTime();
        if (attempt.getStartTime() != null && finished != null) {
            out.setTimeTakenSeconds(Duration.between(attempt.getStartTime(), finished).getSeconds());
        }

        studentRepository.findById(attempt.getStudentId()).ifPresent(s -> {
            out.setStudentName(s.getName());
            out.setHallTicket(s.getHallTicket());
        });
        examRepository.findById(attempt.getExamId()).ifPresent(ex -> out.setExamTitle(ex.getTitle()));

        // Where this candidate stands against everyone else who sat the paper.
        // Added last, because it needs the section breakdown already in place.
        rankingService.enrich(out, attempt);

        return out;
    }

    private Map<Long, Question> loadQuestions(List<AttemptQuestion> layout) {
        List<Long> ids = layout.stream().map(AttemptQuestion::getQuestionId).toList();
        Map<Long, Question> map = new HashMap<>();
        for (Question q : questionRepository.findAllById(ids)) map.put(q.getId(), q);
        return map;
    }

    private Map<Long, Answer> answersByQuestion(Long attemptId) {
        Map<Long, Answer> map = new HashMap<>();
        for (Answer a : answerRepository.findByAttemptId(attemptId)) map.put(a.getQuestionId(), a);
        return map;
    }
}
