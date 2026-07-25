package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {

    /** Tenant-scoped listing — never use findAll() on an admin path. */
    List<Exam> findByAdminIdOrderByIdDesc(Long adminId);

    Optional<Exam> findByIdAndAdminId(Long id, Long adminId);
}
