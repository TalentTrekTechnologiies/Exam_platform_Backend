package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Attempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    Optional<Attempt> findByStudentIdAndExamId(Long studentId, Long examId);

    List<Attempt> findByStudentId(Long studentId);

    /** How many papers exist for an exam — prepared plus already begun. */
    long countByExamId(Long examId);

    /** How many candidates have actually begun, i.e. are no longer PENDING. */
    long countByExamIdAndStatusNot(Long examId, String status);

    /**
     * Candidates actually writing right now: started, time not up, and their
     * exam screen heard from in the last two minutes (it polls every twenty
     * seconds). Started-and-in-time alone counted a candidate who had closed
     * the browser and gone home as "writing" until a three-hour deadline ran
     * out, and that blocked deleting or unpublishing an exam nobody was in.
     */
    @Query(value = """
            SELECT COUNT(*) FROM attempts
             WHERE exam_id = :examId
               AND status = 'STARTED'
               AND end_time > NOW()
               AND COALESCE(last_seen_at, start_time) > NOW() - INTERVAL 2 MINUTE
            """, nativeQuery = true)
    long countWritingNow(@Param("examId") Long examId);
}