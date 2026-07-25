package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.repository.AdminRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Public lookup of an institution by its URL slug.
 *
 * The candidate sign-in page needs to show the right college name and logo
 * before anyone has authenticated, so this is deliberately unauthenticated — but
 * it returns branding only. No email, no counts, nothing that would let the
 * endpoint be used to enumerate an institution's data.
 */
@RestController
@RequestMapping("/public/institution")
public class InstitutionController {

    private final AdminRepository adminRepository;

    public InstitutionController(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    @GetMapping("/{code}")
    public Map<String, Object> byCode(@PathVariable String code) {
        var admin = adminRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new NoSuchElementException("No such institution"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", admin.getCode());
        out.put("collegeName", admin.getCollegeName());
        out.put("collegeLogo", admin.getCollegeLogo());
        return out;
    }
}
