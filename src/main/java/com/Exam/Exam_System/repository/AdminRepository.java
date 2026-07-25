package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Resolves an institution from its URL slug, e.g. "ksrm". */
    Optional<Admin> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
