package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.service.SectionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/section")
public class SectionController {

    private final SectionService sectionService;
    private final AccessGuard accessGuard;

    public SectionController(SectionService sectionService, AccessGuard accessGuard) {
        this.sectionService = sectionService;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public Section createSection(@RequestBody Section section) {
        accessGuard.requireOwnedExam(section.getExamId());
        if (section.getName() == null || section.getName().isBlank()) {
            throw new IllegalArgumentException("Section name is required.");
        }
        return sectionService.createSection(section);
    }

    @GetMapping("/{examId}")
    public List<Section> getSections(@PathVariable Long examId) {
        accessGuard.requireOwnedExam(examId);
        return sectionService.getSectionsByExam(examId);
    }

    @PutMapping("/{id}")
    public Section updateSection(@PathVariable Long id, @RequestBody Section updatedSection) {
        accessGuard.requireOwnedSection(id);
        return sectionService.updateSection(id, updatedSection);
    }

    @DeleteMapping("/{id}")
    public void deleteSection(@PathVariable Long id) {
        accessGuard.requireOwnedSection(id);
        sectionService.deleteSection(id);
    }
}
