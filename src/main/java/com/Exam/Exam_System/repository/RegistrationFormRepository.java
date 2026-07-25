package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.RegistrationForm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RegistrationFormRepository extends JpaRepository<RegistrationForm, Long> {

    Optional<RegistrationForm> findByToken(String token);

    Optional<RegistrationForm> findByExamId(Long examId);
}
