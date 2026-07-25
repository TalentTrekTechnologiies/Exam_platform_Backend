package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Answer;
import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.repository.AnswerRepository;
import com.Exam.Exam_System.repository.AttemptQuestionRepository;
import com.Exam.Exam_System.repository.AttemptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AnswerService {

    /**
     * The whole answer-save, as one statement.
     *
     * Every guard that used to be a separate round trip is now a predicate:
     *   · the attempt exists                    → WHERE a.id = ?
     *   · it belongs to this candidate          → AND a.student_id = ?
     *   · it is live, not submitted             → AND a.status = 'STARTED'
     *   · time has not run out                  → AND a.end_time > NOW()
     *   · the question is on THIS paper         → JOIN attempt_questions
     *
     * If any predicate fails the SELECT yields no row and nothing is written, so
     * the statement is safe by construction rather than by prior checking. This
     * takes the hottest write in the system from five round trips to one — the
     * difference between ~15,000 queries/sec and ~3,000 at full national scale.
     */
    private static final String UPSERT = """
            INSERT INTO answers (attempt_id, question_id, selected_option, is_correct, updated_at)
            SELECT a.id, aq.question_id, ?, NULL, NOW(6)
              FROM attempts a
              JOIN attempt_questions aq
                ON aq.attempt_id = a.id AND aq.question_id = ?
             WHERE a.id = ?
               AND a.student_id = ?
               AND a.status = 'STARTED'
               AND a.end_time > NOW()
            ON DUPLICATE KEY UPDATE
               selected_option = VALUES(selected_option),
               is_correct      = NULL,
               updated_at      = NOW(6)
            """;

    private final AnswerRepository answerRepository;
    private final AttemptRepository attemptRepository;
    private final AttemptQuestionRepository attemptQuestionRepository;
    private final ScoringService scoringService;
    private final JdbcTemplate jdbcTemplate;

    public AnswerService(AnswerRepository answerRepository,
                         AttemptRepository attemptRepository,
                         AttemptQuestionRepository attemptQuestionRepository,
                         ScoringService scoringService,
                         JdbcTemplate jdbcTemplate) {
        this.answerRepository = answerRepository;
        this.attemptRepository = attemptRepository;
        this.attemptQuestionRepository = attemptQuestionRepository;
        this.scoringService = scoringService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records a response. `selectedOption` is the canonical option letter (A–D);
     * null or blank clears it, which real exams always allow.
     *
     * Correctness is deliberately not evaluated here — it is computed at submit,
     * so nothing about the answer key is observable while the exam is live.
     */
    public String saveAnswer(Long attemptId, Long studentId, Long questionId, String selectedOption) {

        String normalized = selectedOption == null ? null : selectedOption.trim().toUpperCase();
        if (normalized != null && normalized.isEmpty()) normalized = null;
        if (normalized != null && !List.of("A", "B", "C", "D").contains(normalized)) {
            throw new IllegalArgumentException("INVALID_OPTION");
        }

        int affected = jdbcTemplate.update(UPSERT, normalized, questionId, attemptId, studentId);

        // Nothing written means either a guard rejected it, or the candidate
        // re-picked the option they already had. Only then do we pay for the
        // slower query that tells the two apart and names the reason.
        if (affected == 0) {
            explainRejection(attemptId, studentId, questionId);
        }
        return normalized;
    }

    /**
     * Works out why the fast path wrote nothing, and throws the specific error.
     * Returns normally when the write was a genuine no-op (same option re-picked).
     */
    private void explainRejection(Long attemptId, Long studentId, Long questionId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));

        if (!attempt.getStudentId().equals(studentId)) {
            throw new AccessDeniedException("That attempt belongs to another candidate.");
        }
        if ("SUBMITTED".equals(attempt.getStatus())) {
            throw new IllegalStateException("EXAM_ALREADY_SUBMITTED");
        }
        if (attempt.getEndTime() == null || LocalDateTime.now().isAfter(attempt.getEndTime())) {
            scoringService.submit(attemptId, "auto-submit: answer received after expiry");
            throw new IllegalStateException("EXAM_TIME_OVER");
        }
        if (!attemptQuestionRepository.existsByAttemptIdAndQuestionId(attemptId, questionId)) {
            throw new IllegalArgumentException("QUESTION_NOT_IN_PAPER");
        }
        // Every guard passes: the row simply already held this exact value.
    }

    /**
     * The candidate's saved responses, for resuming after a refresh or a crash.
     * Returns only what they picked — never whether it was right.
     */
    @Transactional(readOnly = true)
    public List<Answer> getAnswersByAttempt(Long attemptId) {
        return answerRepository.findByAttemptId(attemptId);
    }
}
