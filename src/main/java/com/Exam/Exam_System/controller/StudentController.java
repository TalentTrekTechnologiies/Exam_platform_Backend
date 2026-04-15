package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.service.StudentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/student")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @PostMapping("/validate")
    public String validate(@RequestBody Map<String, String> request) {

        return studentService.validateStudent(
                request.get("hallTicket"),
                request.get("name")
        );
    }
}