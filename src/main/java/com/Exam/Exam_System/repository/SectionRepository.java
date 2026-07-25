package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Section;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<Section, Long> {
	List<Section> findByExamId(Long examId);
}