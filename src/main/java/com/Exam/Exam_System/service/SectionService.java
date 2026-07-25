package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.repository.SectionRepository;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class SectionService {

    private final SectionRepository sectionRepository;

    public SectionService(SectionRepository sectionRepository) {
        this.sectionRepository = sectionRepository;
    }

    public Section createSection(Section section) {
        return sectionRepository.save(section);
    }

    public List<Section> getSectionsByExam(Long examId) {
        return sectionRepository.findByExamId(examId);
    }

    // ✅ NEW UPDATE
    public Section updateSection(Long id, Section updatedSection) {
        Section existing = sectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Section not found"));

        existing.setName(updatedSection.getName());
        existing.setTotalMarks(updatedSection.getTotalMarks());

        return sectionRepository.save(existing);
    }

    // ✅ NEW DELETE
    public void deleteSection(Long id) {
        sectionRepository.deleteById(id);
    }
}