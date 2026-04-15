package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Student;
import com.Exam.Exam_System.repository.StudentRepository;
import org.springframework.stereotype.Service;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    public String validateStudent(String hallTicket, String name) {

        Student student = studentRepository
                .findByHallTicketAndName(hallTicket, name)
                .orElseThrow(() -> new RuntimeException("Invalid student"));

        return "Allowed";
    }
}