package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Answer;
import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.ExamStudent;
import com.Exam.Exam_System.dto.PaperQuestionResponse;
import com.Exam.Exam_System.dto.ResultResponse;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.security.CurrentUser;
import com.Exam.Exam_System.security.JwtService;
import com.Exam.Exam_System.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/student")
public class StudentController {

    private final StudentService studentService;
    private final SlotService slotService;
    private final AttemptService attemptService;
    private final AnswerService answerService;
    private final PaperService paperService;
    private final ExamStudentRepository examStudentRepository;
    private final ExamRepository examRepository;
    private final com.Exam.Exam_System.repository.AdminRepository adminRepository;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final AccessGuard accessGuard;
    private final ProctoringService proctoringService;
    private final SessionWatch sessionWatch;
    private final ProctorFrameService proctorFrameService;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StudentController.class);

    public StudentController(StudentService studentService,
                             SlotService slotService,
                             AttemptService attemptService,
                             AnswerService answerService,
                             PaperService paperService,
                             ExamStudentRepository examStudentRepository,
                             ExamRepository examRepository,
                             com.Exam.Exam_System.repository.AdminRepository adminRepository,
                             JwtService jwtService,
                             CurrentUser currentUser,
                             AccessGuard accessGuard,
                             ProctoringService proctoringService,
                             SessionWatch sessionWatch,
                             ProctorFrameService proctorFrameService) {
        this.sessionWatch = sessionWatch;
        this.proctorFrameService = proctorFrameService;
        this.studentService = studentService;
        this.slotService = slotService;
        this.attemptService = attemptService;
        this.answerService = answerService;
        this.paperService = paperService;
        this.examStudentRepository = examStudentRepository;
        this.examRepository = examRepository;
        this.adminRepository = adminRepository;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.proctoringService = proctoringService;
    }

    /**
     * The one public student endpoint. Verifies the hall ticket against the slot
     * window and issues a token scoped to that candidate and that exam.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, String> request) {
        String hallTicket = request.getOrDefault("hallTicket", "");
        String name = request.getOrDefault("name", "");
        String institutionCode = request.getOrDefault("institutionCode", "");

        if (hallTicket.isBlank() || name.isBlank()) {
            return denied("Enter both your hall ticket number and your name.");
        }

        // Each college has its own entrance URL, which is how identical roll
        // numbers at different institutions stay unambiguous. Absent (a
        // single-college deployment), the lookup falls back to the whole platform.
        Long institutionId = null;
        if (!institutionCode.isBlank()) {
            var institution = adminRepository.findByCodeIgnoreCase(institutionCode.trim());
            if (institution.isEmpty()) {
                return denied("That exam link is not valid. Check with your invigilator.");
            }
            institutionId = institution.get().getId();
        }

        var candidate = studentService.findCandidate(institutionId, hallTicket, name);
        if (candidate.isEmpty()) {
            // Same wording whether the hall ticket is unknown or the name simply
            // doesn't match it, so the form can't be used to probe the roll list.
            return denied("Those details don't match our records. Check with your invigilator.");
        }
        var student = candidate.get();

        List<ExamStudent> mappings = examStudentRepository.findByStudentId(student.getId());
        if (mappings.isEmpty()) {
            return denied("You are not assigned to any exam.");
        }

        LocalDateTime now = LocalDateTime.now();
        ExamStudent valid = null;
        LocalDateTime nextStart = null;
        // Whether anything stopped them other than the clock. Without this, an
        // exam the staff had not published yet turned candidates away saying
        // their window had closed — sending an invigilator to check the
        // timetable when the paper simply had not been released.
        boolean awaitingRelease = false;

        for (ExamStudent mapping : mappings) {
            if (mapping.getSlotId() == null) continue;

            // An exam still being built is not sittable, whatever its slot says.
            // This is the guard that stops a half-written paper with an unchecked
            // answer key being walked into by a candidate who has the link.
            var examForMapping = examRepository.findById(mapping.getExamId()).orElse(null);
            if (examForMapping == null) continue;
            if (!examForMapping.isPublished()) { awaitingRelease = true; continue; }

            var slot = slotService.getSlotById(mapping.getSlotId());

            if (!now.isBefore(slot.getStartTime()) && !now.isAfter(slot.getEndTime())) {
                valid = mapping;
                break;
            }
            if (now.isBefore(slot.getStartTime()) && (nextStart == null || slot.getStartTime().isBefore(nextStart))) {
                nextStart = slot.getStartTime();
            }
        }

        if (valid == null) {
            if (nextStart != null) {
                return denied("Your exam has not started yet. It opens at " + nextStart + ".");
            }
            // Says which of the two it is, so the right person fixes the right
            // thing: the invigilator releases the paper, or checks the sitting.
            return denied(awaitingRelease
                    ? "Your exam has not been released yet. Ask your invigilator to publish it."
                    : "Your exam window has closed.");
        }

        Attempt prior = attemptService.getAttempt(student.getId(), valid.getExamId());
        if (prior != null && "SUBMITTED".equals(prior.getStatus())) {
            return denied("You have already submitted this exam.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "Allowed");
        body.put("token", jwtService.issueStudentToken(
                student.getId(), student.getHallTicket(), valid.getExamId()));
        body.put("studentId", student.getId());
        body.put("studentName", student.getName());
        body.put("hallTicket", student.getHallTicket());
        body.put("examId", valid.getExamId());
        body.put("slotId", valid.getSlotId());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> denied(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "Denied");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Starts or resumes the attempt.
     *
     * The candidate is taken from the token, not the request body — the old
     * version trusted a client-supplied studentId, so anyone could start an
     * attempt as any candidate.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startExam(@RequestBody Map<String, Object> request) {
        Long studentId = currentUser.studentId();

        Object examObj = request.get("examId");
        if (examObj == null) throw new IllegalArgumentException("examId is required.");
        Long examId = Long.parseLong(examObj.toString());

        accessGuard.requireTokenMatchesExam(examId);

        ExamStudent mapping = examStudentRepository.findByStudentIdAndExamId(studentId, examId);
        if (mapping == null) {
            throw new IllegalArgumentException("You are not registered for this exam.");
        }

        Attempt attempt = attemptService.startOrResume(studentId, examId, mapping.getSlotId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", attempt.getId());
        body.put("status", attempt.getStatus());
        body.put("remainingSeconds", attemptService.getRemainingSeconds(attempt.getId()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/paper/{attemptId}")
    public List<PaperQuestionResponse> getPaper(@PathVariable Long attemptId) {
        accessGuard.requireOwnAttempt(attemptId);
        return paperService.getPaper(attemptId);
    }

    @GetMapping("/responses/{attemptId}")
    public Map<Long, String> getResponses(@PathVariable Long attemptId) {
        accessGuard.requireOwnAttempt(attemptId);

        Map<Long, String> responses = new LinkedHashMap<>();
        for (Answer a : answerService.getAnswersByAttempt(attemptId)) {
            if (a.getSelectedOption() != null) responses.put(a.getQuestionId(), a.getSelectedOption());
        }
        return responses;
    }


    /**
     * The candidate's newest camera frame, for the invigilator's live view.
     *
     * Scoped by the attempt's own student id, so a candidate can only ever put
     * a picture against their own seat. Always answers 200: a camera that
     * stutters is the invigilator's problem to notice, never an error thrown
     * into the middle of somebody's exam.
     */
    @PostMapping("/proctor/frame")
    public Map<String, Object> uploadFrame(@RequestParam("attemptId") Long attemptId,
                                           @RequestParam("frame") MultipartFile frame) {
        boolean stored = false;
        try {
            // The attempt has to be this candidate's own, or a frame could be
            // put against someone else's seat.
            var attempt = attemptService.requireAttempt(attemptId);
            if (attempt != null && currentUser.studentId().equals(attempt.getStudentId())) {
                stored = proctorFrameService.store(attemptId, frame);
            }
        } catch (Exception e) {
            log.debug("Frame upload failed for attempt {}: {}", attemptId, e.toString());
        }
        return Map.of("stored", stored);
    }

    @PostMapping("/answer")
    public ResponseEntity<Map<String, Object>> saveAnswer(@RequestBody Map<String, Object> request) {
        Object attemptObj = request.get("attemptId");
        Object questionObj = request.get("questionId");
        Object optionObj = request.get("selectedOption");

        if (attemptObj == null || questionObj == null) {
            throw new IllegalArgumentException("attemptId and questionId are required.");
        }

        Long attemptId = Long.parseLong(attemptObj.toString());
        Long questionId = Long.parseLong(questionObj.toString());

        // No separate ownership lookup: the save statement itself is scoped to
        // this candidate's attempt, so a mismatch simply writes nothing.
        String saved = answerService.saveAnswer(
                attemptId,
                currentUser.studentId(),
                questionId,
                optionObj == null ? null : optionObj.toString());

        // Two sessions writing to one attempt within the same couple of minutes
        // means this hall ticket is in two people's hands. Checked on the answer
        // path because that is where the second person's activity actually shows
        // up — they are there to answer, not to poll a clock. Raised once per
        // attempt, and never allowed to fail the save itself: an integrity flag
        // must not cost a legitimate candidate their answer.
        try {
            if (sessionWatch.observe(attemptId, currentUser.session())) {
                proctoringService.record(attemptId, "CONCURRENT_SESSION", null,
                        "This hall ticket was answering from two devices at the same time.");
            }
        } catch (Exception e) {
            log.warn("Concurrent-session check failed for attempt {}: {}", attemptId, e.toString());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questionId", questionId);
        body.put("selectedOption", saved);
        body.put("saved", true);
        return ResponseEntity.ok(body);
    }

    /**
     * Records a proctoring event. Fire-and-forget from the candidate's side: it
     * must never block or fail their exam, but it gives the invigilator an
     * auditable record that survives the candidate clearing their browser.
     *
     * The type is validated against a fixed set so a crafted client cannot write
     * arbitrary text into the integrity log.
     */
    @PostMapping("/violation")
    public ResponseEntity<Map<String, Object>> recordViolation(@RequestBody Map<String, Object> request) {
        Object attemptObj = request.get("attemptId");
        if (attemptObj == null) throw new IllegalArgumentException("attemptId is required.");

        Long attemptId = Long.parseLong(attemptObj.toString());
        accessGuard.requireOwnAttempt(attemptId);

        String type = String.valueOf(request.getOrDefault("type", "")).trim().toUpperCase();
        boolean isCameraObservation = CAMERA_OBSERVATIONS.contains(type);
        if (!ALLOWED_VIOLATIONS.contains(type) && !isCameraObservation) {
            throw new IllegalArgumentException("Unknown violation type.");
        }

        Object occurrence = request.get("occurrence");
        Object detail = request.get("detail");

        long total = proctoringService.record(
                attemptId,
                type,
                occurrence == null ? null : Integer.parseInt(occurrence.toString()),
                detail == null ? null : detail.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recorded", true);
        body.put("totalViolations", total);
        // The client must know whether this event counts against the candidate.
        // A camera observation is logged for review and must never trigger a
        // warning dialog or the auto-submit countdown.
        body.put("countsTowardLimit", !isCameraObservation);
        return ResponseEntity.ok(body);
    }

    /**
     * Browser events the candidate is warned about, and which count toward the
     * three strikes that end an exam. These are unambiguous: the candidate
     * actively left the exam window.
     */
    private static final Set<String> ALLOWED_VIOLATIONS =
            Set.of("FULLSCREEN_EXIT", "TAB_SWITCH", "APP_SWITCH", "AUTO_SUBMIT");

    /**
     * Camera observations. Recorded for an invigilator to review — and
     * deliberately NOT counted toward auto-submission.
     *
     * Face detection is probabilistic. A candidate who looks down to think, sits
     * in a dim room, wears glasses, or has a window behind them will sometimes
     * register as "no face". Ending a real student's exam on that basis would be
     * a far worse failure than missing a cheat, so these inform a human decision
     * rather than making one.
     */
    private static final Set<String> CAMERA_OBSERVATIONS =
            Set.of("FACE_ABSENT", "MULTIPLE_FACES", "CAMERA_BLOCKED");

    @GetMapping("/remaining/{attemptId}")
    public Map<String, Object> getRemaining(@PathVariable Long attemptId) {
        accessGuard.requireOwnAttempt(attemptId);

        long remaining = attemptService.getRemainingSeconds(attemptId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remainingSeconds", remaining);
        data.put("expired", remaining == 0);
        return data;
    }

    @PostMapping("/submit/{attemptId}")
    public ResponseEntity<Map<String, Object>> submitExam(
            @PathVariable Long attemptId,
            @RequestParam(required = false, defaultValue = "candidate submitted") String reason) {

        accessGuard.requireOwnAttempt(attemptId);
        Attempt attempt = attemptService.submitAttempt(attemptId, reason);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", attempt.getId());
        body.put("status", attempt.getStatus());
        body.put("submitted", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/result/{attemptId}")
    public ResultResponse getResult(@PathVariable Long attemptId) {
        accessGuard.requireOwnAttempt(attemptId);
        return attemptService.getResult(attemptId);
    }

    @GetMapping("/duration/{examId}")
    public Map<String, Object> getDuration(@PathVariable Long examId) {
        accessGuard.requireTokenMatchesExam(examId);
        var exam = examRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("Exam not found"));
        return Map.of("duration", exam.getDuration());
    }

    /** Branding and proctoring flags for the exam shell. */
    @GetMapping("/exam-info/{examId}")
    public Map<String, Object> getExamInfo(@PathVariable Long examId) {
        accessGuard.requireTokenMatchesExam(examId);

        var exam = examRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("Exam not found"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", exam.getTitle());
        data.put("collegeName", exam.getCollegeName());
        data.put("collegeLogo", exam.getCollegeLogo());
        data.put("introVideo", exam.getIntroVideo());
        data.put("duration", exam.getDuration());
        data.put("enableCamera", exam.isEnableCamera());
        data.put("enableMic", exam.isEnableMic());
        return data;
    }

    /**
     * The paper's shape, for the pre-exam briefing: sections, how many questions
     * each holds, and the marking scheme. Carries no question text and no answers,
     * so it is safe to serve before the attempt starts.
     */
    @GetMapping("/exam-structure/{examId}")
    public Map<String, Object> getExamStructure(@PathVariable Long examId) {
        accessGuard.requireTokenMatchesExam(examId);
        return paperService.describeStructure(examId);
    }

    /** Alias kept so older clients calling exam-details keep working. */
    @GetMapping("/exam-details/{examId}")
    public Map<String, Object> getExamDetails(@PathVariable Long examId) {
        return getExamInfo(examId);
    }
}
