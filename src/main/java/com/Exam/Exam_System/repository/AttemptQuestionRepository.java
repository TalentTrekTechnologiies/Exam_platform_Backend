package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.AttemptQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttemptQuestionRepository extends JpaRepository<AttemptQuestion, Long> {

    List<AttemptQuestion> findByAttemptIdOrderByDisplayOrderAsc(Long attemptId);

    boolean existsByAttemptId(Long attemptId);

    /** How many questions are frozen onto this paper. */
    long countByAttemptId(Long attemptId);

    /** Discards a frozen paper so it can be rebuilt. Only ever for a PENDING attempt. */
    void deleteByAttemptId(Long attemptId);

    /**
     * Cheap membership check for the answer hot path. Saving a single answer used
     * to load the candidate's entire frozen layout (~180 rows) just to confirm
     * the question belonged to it — an O(n) read on every keystroke, multiplied
     * by every candidate. This is one indexed lookup instead.
     */
    boolean existsByAttemptIdAndQuestionId(Long attemptId, Long questionId);
}
