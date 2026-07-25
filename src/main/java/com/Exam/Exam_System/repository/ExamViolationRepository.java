package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.ExamViolation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamViolationRepository extends JpaRepository<ExamViolation, Long> {

    List<ExamViolation> findByAttemptIdOrderByOccurredAtAsc(Long attemptId);

    long countByAttemptId(Long attemptId);

    /**
     * Counts only the given event types.
     *
     * Used to tally the strikes that end an exam, so camera observations — which
     * are probabilistic and review-only — can be recorded in full without ever
     * contributing to auto-submission.
     */
    long countByAttemptIdAndTypeIn(Long attemptId, java.util.Collection<String> types);

    /**
     * The integrity report for one exam: every candidate who triggered a
     * violation, worst offenders first. Joined in the database and scoped to the
     * owning institution.
     */
    @Query("""
            SELECT s.hallTicket, s.name, v.attemptId, COUNT(v), MAX(v.occurredAt)
              FROM ExamViolation v
              JOIN Student s ON s.id = v.studentId
              JOIN Exam e    ON e.id = v.examId
             WHERE e.adminId = :adminId
               AND (:examId IS NULL OR v.examId = :examId)
             GROUP BY s.hallTicket, s.name, v.attemptId
             ORDER BY COUNT(v) DESC
            """)
    List<Object[]> summariseForAdmin(@Param("adminId") Long adminId, @Param("examId") Long examId);
}
