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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.Exam.Exam_System.service.ProctorFrameService proctorFrames;
    private final com.Exam.Exam_System.service.AttemptCache attemptCache;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExamController.class);

    public ExamController(ExamRepository examRepository,
                          AdminRepository adminRepository,
                          CurrentUser currentUser,
                          AccessGuard accessGuard,
                          AttemptService attemptService,
                          PublicationService publicationService,
                          SlotRepository slotRepository,
                          org.springframework.jdbc.core.JdbcTemplate jdbc,
                          com.Exam.Exam_System.service.ProctorFrameService proctorFrames,
                          com.Exam.Exam_System.service.AttemptCache attemptCache) {
        this.attemptCache = attemptCache;
        this.proctorFrames = proctorFrames;
        this.slotRepository = slotRepository;
        this.examRepository = examRepository;
        this.adminRepository = adminRepository;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.attemptService = attemptService;
        this.publicationService = publicationService;
        this.jdbc = jdbc;
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
        Integer previousDuration = exam.getDuration();
        if (updated.getDuration() != null && updated.getDuration() > 0) exam.setDuration(updated.getDuration());
        if (updated.getStartDate() != null) exam.setStartDate(updated.getStartDate());
        if (updated.getEndDate() != null) exam.setEndDate(updated.getEndDate());
        if (updated.getCollegeName() != null) exam.setCollegeName(updated.getCollegeName());
        if (updated.getCollegeLogo() != null) exam.setCollegeLogo(updated.getCollegeLogo());
        if (updated.getIntroVideo() != null) exam.setIntroVideo(updated.getIntroVideo());
        exam.setEnableCamera(updated.isEnableCamera());
        exam.setEnableMic(updated.isEnableMic());

        Exam saved = examRepository.save(exam);

        // A new duration applies to papers being written now, not only to
        // those started later. A deadline is fixed when a paper starts, so an
        // exam created at the default 180 minutes and corrected to 10 after
        // candidates began left them three hours -- "the time is over and it
        // still opens". Each running paper is re-timed from its own start,
        // keeping any time returned for a dropped connection; one already past
        // the new time is finished on its next clock poll, answers kept.
        if (saved.getDuration() != null && !saved.getDuration().equals(previousDuration)) {
            List<Long> running = jdbc.queryForList(
                    "SELECT id FROM attempts WHERE exam_id = ? AND status = 'STARTED' AND start_time IS NOT NULL",
                    Long.class, examId);
            if (!running.isEmpty()) {
                jdbc.update("""
                        UPDATE attempts
                           SET end_time = DATE_ADD(start_time, INTERVAL (? * 60 + extended_seconds) SECOND)
                         WHERE exam_id = ? AND status = 'STARTED' AND start_time IS NOT NULL
                        """, saved.getDuration(), examId);
                running.forEach(attemptCache::invalidate);
                log.warn("Exam {} duration {} -> {} minutes by admin {}; re-timed {} running paper(s).",
                        examId, previousDuration, saved.getDuration(), currentUser.adminId(), running.size());
            }
        }
        return saved;
    }

    /**
     * Deletes an exam, and everything that only existed because of it.
     *
     * Refused the moment anybody has actually sat it. Deleting a paper takes
     * every candidate's answers and marks with it, and there is no undo — the
     * same reason removing a candidate keeps their results rather than erasing
     * them. A finished exam is a record, not clutter. Unpublish it instead; it
     * disappears from candidates without disappearing from Results.
     *
     * What it IS for is the exam built by mistake: a test paper, a duplicate, a
     * draft nobody sat. Those are common, and having no way to remove them
     * leaves an exam list nobody trusts.
     *
     * Deleted in dependency order, so a failure part-way leaves nothing
     * referring to a row that has gone.
     */
    @DeleteMapping("/{examId}")
    public org.springframework.http.ResponseEntity<Map<String, Object>> deleteExam(
            @PathVariable Long examId,
            @RequestParam(defaultValue = "false") boolean includeResults) {
        accessGuard.requireOwnedExam(examId);

        Integer sat = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_id = ? AND status <> 'PENDING'",
                Integer.class, examId);
        if (sat != null && sat > 0 && !includeResults) {
            // Said as a code the screen can act on: it offers deleting the
            // results too, as a second, separately confirmed step.
            Map<String, Object> refused = new java.util.LinkedHashMap<>();
            refused.put("status", 409);
            refused.put("code", "HAS_RESULTS");
            refused.put("sat", sat);
            refused.put("message", sat + " candidate(s) have already sat this exam, so deleting it would erase"
                    + " their answers and marks. Unpublish it instead — it disappears from"
                    + " candidates without disappearing from Results.");
            return org.springframework.http.ResponseEntity.status(409).body(refused);
        }

        // Even with results included, never from under somebody mid-paper.
        Integer writing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_id = ? AND status = 'STARTED' AND end_time > NOW()",
                Integer.class, examId);
        if (writing != null && writing > 0) {
            throw new IllegalStateException(writing + " candidate(s) are writing this exam right now. "
                    + "Delete it once they have submitted.");
        }
        if (sat != null && sat > 0) {
            log.warn("Exam {} deleted with the results of {} candidate(s), by admin {}.",
                    examId, sat, currentUser.adminId());
        }

        // Webcam stills are files, not rows: gone with the attempts they belong to.
        java.util.List<Long> attemptIds = jdbc.queryForList(
                "SELECT id FROM attempts WHERE exam_id = ?", Long.class, examId);

        // Papers, answers and integrity records, for every attempt.
        jdbc.update("""
                DELETE aq FROM attempt_questions aq
                  JOIN attempts a ON a.id = aq.attempt_id
                 WHERE a.exam_id = ?
                """, examId);
        jdbc.update("""
                DELETE ans FROM answers ans
                  JOIN attempts a ON a.id = ans.attempt_id
                 WHERE a.exam_id = ?
                """, examId);
        jdbc.update("""
                DELETE v FROM exam_violations v
                  JOIN attempts a ON a.id = v.attempt_id
                 WHERE a.exam_id = ?
                """, examId);
        jdbc.update("DELETE FROM attempts WHERE exam_id = ?", examId);

        // The paper itself. Test cases first: nothing else refers to them, so
        // they would otherwise be rows with no way back to them.
        jdbc.update("""
                DELETE tc FROM test_cases tc
                  JOIN questions q ON q.id = tc.question_id
                 WHERE q.exam_id = ?
                """, examId);
        jdbc.update("DELETE FROM questions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM sections WHERE exam_id = ?", examId);

        // The sitting: who was on it, and when it ran.
        jdbc.update("DELETE FROM exam_student WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exam_slots WHERE exam_id = ?", examId);

        examRepository.deleteById(examId);
        log.warn("Exam {} and its sittings deleted by admin {} ({} result(s) removed with it).",
                examId, currentUser.adminId(), sat == null ? 0 : sat);
        try {
            proctorFrames.discard(attemptIds);
        } catch (RuntimeException e) {
            log.debug("Could not discard proctor frames for exam {}: {}", examId, e.toString());
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("deleted", true);
        out.put("examId", examId);
        out.put("resultsDeleted", sat == null ? 0 : sat);
        return org.springframework.http.ResponseEntity.ok(out);
    }
}
