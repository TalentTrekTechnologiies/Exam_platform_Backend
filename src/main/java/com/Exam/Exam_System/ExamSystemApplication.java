package com.Exam.Exam_System;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is on for the mail outbox drain. Without @EnableScheduling the
 * @Scheduled drain is never invoked at all — the queue fills, nothing leaves it,
 * and there is no error anywhere to say why.
 */
@SpringBootApplication
@EnableScheduling
public class ExamSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExamSystemApplication.class, args);
	}

}
