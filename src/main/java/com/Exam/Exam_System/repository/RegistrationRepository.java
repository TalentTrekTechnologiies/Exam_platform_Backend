package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Registration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistrationRepository extends JpaRepository<Registration, Long> {

    List<Registration> findByFormIdOrderBySubmittedAtAsc(Long formId);

    List<Registration> findByExamIdAndStatusOrderBySubmittedAtAsc(Long examId, String status);

    Optional<Registration> findByFormIdAndEmailIgnoreCase(Long formId, String email);

    long countByFormId(Long formId);

    long countByFormIdAndStatus(Long formId, String status);
}
