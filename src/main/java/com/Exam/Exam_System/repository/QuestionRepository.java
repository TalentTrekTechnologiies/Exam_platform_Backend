package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
	

	List<Question> findByExamId(Long examId);
	List<Question> findAll();
}