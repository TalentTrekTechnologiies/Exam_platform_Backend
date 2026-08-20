package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findByQuestionIdOrderByDisplayOrderAscIdAsc(Long questionId);

    /** What "Compile & Run" executes against — the cases the candidate can see. */
    List<TestCase> findByQuestionIdAndSampleTrueOrderByDisplayOrderAscIdAsc(Long questionId);

    long countByQuestionId(Long questionId);

    void deleteByQuestionId(Long questionId);
}
