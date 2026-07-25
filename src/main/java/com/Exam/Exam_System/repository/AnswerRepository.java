package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
	Answer findByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    List<Answer> findByAttemptId(Long attemptId);
}