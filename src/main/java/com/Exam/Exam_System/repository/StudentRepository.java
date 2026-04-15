package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByHallTicketAndName(String hallTicket, String name);
}