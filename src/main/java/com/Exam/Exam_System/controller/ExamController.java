package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Admin;
import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.Entity.Slot;
import com.Exam.Exam_System.dto.PublicationStatus;
import com.Exam.Exam_System.repository.AdminRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.SlotRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.security.CurrentUser;
import com.Exam.Exam_System.service.AttemptService;
import com.Exam.Exam_System.service.PublicationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
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
    private final SlotRepository slotRepository;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExamController.class);

    public ExamController(ExamRepository examRepository,
                          AdminRepository adminRepository,
                          CurrentUser currentUser,
                          AccessGuard accessGuard,
                          AttemptService attemptService,
                          PublicationService publicationService,
                          SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
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

        Exam saved = examRepository.save(exam);

        // The exam's own start/end were previously decorative: candidate sign-in
        // checks the SLOT window and nothing else, so an admin who set 9–10 here
        // and then never opened the Slots screen had an exam nobody could enter,
        // with nothing explaining why. Creating the matching slot up front makes
        // the dates mean what they plainly appear to mean. More slots can still
        // be added for a second sitting; this is the first one, not the only one.
        try {
            if (exam.getSlots() != null && !exam.getSlots().isEmpty()) {
                // Sittings stated explicitly: a morning and an evening batch,
                // say. Create exactly those and no blanket one over the top,
                // which would let every candidate sit at any hour of the day.
                int made = 0;
                for (Exam.SlotWindow window : exam.getSlots()) {
                    if (window == null || window.getStartTime() == null || window.getEndTime() == null) continue;
                    if (!window.getEndTime().isAfter(window.getStartTime())) continue;
                    Slot slot = new Slot();
                    slot.setExamId(saved.getId());
                    slot.setStartTime(window.getStartTime());
                    slot.setEndTime(window.getEndTime());
                    slotRepository.save(slot);
                    made++;
                }
                if (made > 0) return saved;
                // Nothing usable in the list; fall through to the single window.
            }

            if (saved.getStartDate() != null && saved.getEndDate() != null
                    && saved.getEndDate().isAfter(saved.getStartDate())) {
                Slot slot = new Slot();
                slot.setExamId(saved.getId());
                slot.setStartTime(saved.getStartDate());
                slot.setEndTime(saved.getEndDate());
                slotRepository.save(slot);
            }
        } catch (RuntimeException e) {
            // An exam that exists without its slots is recoverable - the admin
            // can add them - so this never fails the creation itself.
            log.warn("Could not create the sittings for exam {}: {}", saved.getId(), e.toString());
        }

        return saved;
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
     * Announces the results, letting candidates see their own scorecard.
     *
     * Until this is called a candidate who submits is told their paper was
     * received and nothing more. Staff see every score throughout — moderation
     * is the point of holding them — so this changes what the candidate sees,
     * never what the college can.
     */
    @PostMapping("/{examId}/release-results")
    public Map<String, Object> releaseResults(@PathVariable Long examId) {
        Exam exam = accessGuard.requireOwnedExam(examId);
        exam.setResultsReleased(true);
        exam.setResultsReleasedAt(LocalDateTime.now());
        examRepository.save(exam);
        return resultState(exam);
    }

    /** Withdraws them again — a key was wrong, a question is being dropped. */
    @PostMapping("/{examId}/hold-results")
    public Map<String, Object> holdResults(@PathVariable Long examId) {
        Exam exam = accessGuard.requireOwnedExam(examId);
        exam.setResultsReleased(false);
        exam.setResultsReleasedAt(null);
        examRepository.save(exam);
        return resultState(exam);
    }

    private Map<String, Object> resultState(Exam exam) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examId", exam.getId());
        out.put("resultsReleased", exam.isResultsReleased());
        out.put("resultsReleasedAt", exam.getResultsReleasedAt());
        out.put("summary", exam.isResultsReleased()
                ? "Results are out. Candidates can now see their scorecards."
                : "Results are held. Candidates see only that their paper was received.");
        return out;
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
