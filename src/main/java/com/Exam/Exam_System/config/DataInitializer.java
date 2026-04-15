package com.Exam.Exam_System.config;

import com.Exam.Exam_System.Entity.User;
import com.Exam.Exam_System.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

	@Bean
	public CommandLineRunner init(UserRepository userRepository, PasswordEncoder passwordEncoder) {
	    return args -> {

	        if (userRepository.findByEmail("admin@gmail.com").isEmpty()) {

	            User user = new User();
	            user.setEmail("admin@gmail.com");
	            user.setPassword(passwordEncoder.encode("123456"));

	            userRepository.save(user);
	        }
	    };
	}
    }
