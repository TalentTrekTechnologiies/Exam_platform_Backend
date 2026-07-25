package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Admin;
import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.dto.PublicationStatus;
import com.Exam.Exam_System.repository.AdminRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.security.CurrentUser;
import com.Exam.Exam_System.service.AttemptService;
import com.Exam.Exam_System.service.PublicationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/exam")
public class ExamController {

    private final ExamRepository examRepository;
    private final AdminRepository adminRepository;
    private final CurrentUser currentUser;
    private final AccessGuard accessGuard;
    private final AttemptService attemptService;
    private final PublicationService publicationService;

    public ExamController(ExamRepository examRepository,
                          AdminRepository adminRepository,
                          CurrentUser currentUser,
                          AccessGuard accessGuard,
                          AttemptService attemptService,
                          PublicationService publicationService) {
        this.examRepository = examRepository;
        this.adminRepository = adminRepository;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.attemptService = attemptService;
        this.publicationService = publicationService;
    }

    @PostMapping
    public Exam createExam(@RequestBody Exam exam) {
        Long adminId = currentUser.adminId();

        if (exam.getTitle() == null || exam.getTitle().isBlank()) {
            throw new IllegalArgumentException("Exam title is required.");
        }
        if (exam.getDuration() == null || exam.getDuration() <= 0) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }
        if (exam.getStartDate() != null && exam.getEndDate() != null
                && exam.getEndDate().isBefore(exam.getStartDate())) {
            throw new IllegalArgumentException("The exam cannot end before it starts.");
        }

        // Ownership is assigned server-side. A client-supplied adminId would let
        // one institution plant exams inside another's account.
        exam.setAdminId(adminId);

        // Branding defaults to the institution's own, so the candidate always
        // sees the right college on the exam shell.
        Admin admin = adminRepository.findById(adminId).orElse(null);
        if (admin != null) {
            if (exam.getCollegeName() == null || exam.getCollegeName().isBlank()) {
                exam.setCollegeName(admin.getCollegeName());
            }
            if (exam.getCollegeLogo() == null || exam.getCollegeLogo().isBlank()) {
                exam.setCollegeLogo(admin.getCollegeLogo());
            }
        }

        return examRepository.save(exam);
    }

    /**
     * Readiness and the candidate link. Safe to poll while building an exam —
     * it is what the admin screen shows as questions and candidates are added.
     */
    @GetMapping("/{examId}/publication")
    public PublicationStatus publicationStatus(@PathVariable Long examId) {
        return publicationService.status(accessGuard.requireOwnedExam(examId));
    }

    /** Opens the exam to candidates. Refused while anything would break the sitting. */
    @PostMapping("/{examId}/publish")
    public PublicationStatus publish(@PathVariable Long examId) {
        return publicationService.publish(accessGuard.requireOwnedExam(examId));
    }

    /** Closes it again. Refused once anyone has started. */
    @PostMapping("/{examId}/unpublish")
    public PublicationStatus unpublish(@PathVariable Long examId) {
        return publicationService.unpublish(accessGuard.requireOwnedExam(examId));
    }

    /**
     * Builds every candidate's paper ahead of the sitting.
     *
     * Run this before exam day. It moves the heaviest work — creating an attempt
     * and freezing a full paper per candidate — off the moment the slot opens,
     * when every candidate clicks Start at once. Safe to re-run as late
     * registrations arrive.
     */
    @PostMapping("/{examId}/prepare")
    public Map<String, Object> preparePapers(@PathVariable Long examId) {
        accessGuard.requireOwnedExam(examId);
        return attemptService.prepareAttempts(examId);
    }

    /** This institution's exams only. */
    @GetMapping
    public List<Exam> listExams() {
        return examRepository.findByAdminIdOrderByIdDesc(currentUser.adminId());
    }

    @GetMapping("/{examId}")
    public Exam getExam(@PathVariable Long examId) {
        return accessGuard.requireOwnedExam(examId);
    }

    @PutMapping("/{examId}")
    public Exam updateExam(@PathVariable Long examId, @RequestBody Exam updated) {
        Exam exam = accessGuard.requireOwnedExam(examId);

        if (updated.getTitle() != null && !updated.getTitle().isBlank()) exam.setTitle(updated.getTitle());
        if (updated.getDuration() != null && updated.getDuration() > 0) exam.setDuration(updated.getDuration());
        if (updated.getStartDate() != null) exam.setStartDate(updated.getStartDate());
        if (updated.getEndDate() != null) exam.setEndDate(updated.getEndDate());
        if (updated.getCollegeName() != null) exam.setCollegeName(updated.getCollegeName());
        if (updated.getCollegeLogo() != null) exam.setCollegeLogo(updated.getCollegeLogo());
        if (updated.getIntroVideo() != null) exam.setIntroVideo(updated.getIntroVideo());
        exam.setEnableCamera(updated.isEnableCamera());
        exam.setEnableMic(updated.isEnableMic());

        return examRepository.save(exam);
    }
}
