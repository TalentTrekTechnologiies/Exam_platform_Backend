package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Attempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    Optional<Attempt> findByStudentIdAndExamId(Long studentId, Long examId);

    List<Attempt> findByStudentId(Long studentId);

    /** How many papers exist for an exam — prepared plus already begun. */
    long countByExamId(Long examId);

    /** How many candidates have actually begun, i.e. are no longer PENDING. */
    long countByExamIdAndStatusNot(Long examId, String status);
}