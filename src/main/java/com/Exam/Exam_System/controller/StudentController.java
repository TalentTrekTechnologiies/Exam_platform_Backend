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
    private final com.Exam.Exam_System.repository.StudentRepository studentRepository;
    private final ExamRepository examRepository;
    private final com.Exam.Exam_System.repository.AdminRepository adminRepository;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final AccessGuard accessGuard;
    private final ProctoringService proctoringService;
    private final SessionWatch sessionWatch;
    private final ProctorFrameService proctorFrameService;
    private final SectionClockService sectionClock;

    /**
     * How long before the sitting opens a candidate may sign in. They spend it
     * watching the college's introduction and reading the rules, so the paper
     * itself opens on the clock instead of after a queue at the door.
     */
    private final long lobbyMinutes;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StudentController.class);

    public StudentController(StudentService studentService,
                             SlotService slotService,
                             AttemptService attemptService,
                             AnswerService answerService,
                             PaperService paperService,
                             ExamStudentRepository examStudentRepository,
                             com.Exam.Exam_System.repository.StudentRepository studentRepository,
                             ExamRepository examRepository,
                             com.Exam.Exam_System.repository.AdminRepository adminRepository,
                             JwtService jwtService,
                             CurrentUser currentUser,
                             AccessGuard accessGuard,
                             ProctoringService proctoringService,
                             SessionWatch sessionWatch,
                             ProctorFrameService proctorFrameService,
                             SectionClockService sectionClock,
                             @org.springframework.beans.factory.annotation.Value("${exam.lobby-minutes:15}") long lobbyMinutes) {
        this.lobbyMinutes = Math.max(0, lobbyMinutes);
        this.sessionWatch = sessionWatch;
        this.proctorFrameService = proctorFrameService;
        this.studentService = studentService;
        this.slotService = slotService;
        this.attemptService = attemptService;
        this.answerService = answerService;
        this.paperService = paperService;
        this.examStudentRepository = examStudentRepository;
        this.studentRepository = studentRepository;
        this.examRepository = examRepository;
        this.adminRepository = adminRepository;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.proctoringService = proctoringService;
        this.sectionClock = sectionClock;
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

        // Every sitting open to this candidate right now, not just the first
        // one found. A college running two exams at once can have somebody on
        // both, and picking whichever the database happened to return first
        // put candidates into the wrong paper with no way to say so.
        List<ExamStudent> openNow = new ArrayList<>();

        // When the candidate has already chosen, only that exam is considered.
        String askedFor = request.getOrDefault("examId", "");
        Long chosenExamId = askedFor.isBlank() ? null : Long.valueOf(askedFor.trim());
        // Whether anything stopped them other than the clock. Without this, an
        // exam the staff had not published yet turned candidates away saying
        // their window had closed — sending an invigilator to check the
        // timetable when the paper simply had not been released.
        boolean awaitingRelease = false;
        // Enrolled against a sitting that no longer exists.
        boolean sittingMissing = false;
        // A paper this candidate has already handed in.
        boolean submittedAny = false;

        for (ExamStudent mapping : mappings) {
            if (mapping.getSlotId() == null) continue;

            // An exam still being built is not sittable, whatever its slot says.
            // This is the guard that stops a half-written paper with an unchecked
            // answer key being walked into by a candidate who has the link.
            var examForMapping = examRepository.findById(mapping.getExamId()).orElse(null);
            if (examForMapping == null) continue;
            if (!examForMapping.isPublished()) { awaitingRelease = true; continue; }

            // Someone who started and has not finished can always sign back in,
            // whatever the time. Their deadline is their own, counted from when
            // they began, and may run past the slot's close: a candidate who
            // started at 5:10, lost power at 5:58 and is back at 6:03 must not
            // be told their window has closed with an hour's paper half written.
            Attempt live = attemptService.getAttempt(student.getId(), mapping.getExamId());
            // Handed in: never offered again. Left in, a candidate enrolled in
            // two papers was shown the one they had finished as a choice.
            if (live != null && "SUBMITTED".equals(live.getStatus())) { submittedAny = true; continue; }
            if (live != null && AttemptService.STARTED.equals(live.getStatus())) {
                if (chosenExamId == null || chosenExamId.equals(mapping.getExamId())) {
                    openNow.add(mapping);
                }
                continue;
            }

            var slot = slotService.findSlot(mapping.getSlotId()).orElse(null);
            if (slot == null) {
                // Their sitting was deleted. Put them in the one that is open
                // now, or the next, and remember it, so the lobby and Start
                // see a real sitting too. Only an exam with no sitting left to
                // come leaves them out.
                slot = slotService.sittingByClock(mapping.getExamId(), now, lobbyMinutes);
                if (slot == null) { sittingMissing = true; continue; }
                mapping.setSlotId(slot.getId());
                examStudentRepository.save(mapping);
                log.info("Candidate {} had no sitting for exam {}; placed in sitting {}.",
                        student.getId(), mapping.getExamId(), slot.getId());
            }

            // The doors open before the paper does: from lobbyMinutes ahead of
            // the start a candidate may come in, watch the introduction and read
            // the rules. The paper itself is still held until the start — that
            // is enforced at /start and on every attempt endpoint, not here.
            LocalDateTime doorsOpen = slot.getStartTime().minusMinutes(lobbyMinutes);
            if (!now.isBefore(doorsOpen) && !now.isAfter(slot.getEndTime())) {
                if (chosenExamId == null || chosenExamId.equals(mapping.getExamId())) {
                    openNow.add(mapping);
                }
                continue;
            }
            if (now.isBefore(doorsOpen) && (nextStart == null || doorsOpen.isBefore(nextStart))) {
                nextStart = doorsOpen;
            }
        }

        if (openNow.size() == 1) {
            valid = openNow.get(0);
        } else if (openNow.size() > 1) {
            /*
             * More than one paper is open to this candidate. Which one they are
             * sitting is theirs to say, not the database's to guess — an exam
             * officer running Physics and Chemistry in overlapping windows
             * cannot be the reason somebody sits the wrong subject.
             */
            List<Map<String, Object>> choices = new ArrayList<>();
            for (ExamStudent m : openNow) {
                var e = examRepository.findById(m.getExamId()).orElse(null);
                if (e == null) continue;
                var slot = slotService.findSlot(m.getSlotId()).orElse(null);
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("examId", m.getExamId());
                choice.put("examTitle", e.getTitle());
                choice.put("duration", e.getDuration());
                choice.put("opensAt", slot == null ? null : slot.getStartTime());
                choice.put("closesAt", slot == null ? null : slot.getEndTime());
                choices.add(choice);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "Choose");
            body.put("message", "You have more than one exam open. Pick the one you are sitting.");
            body.put("exams", choices);
            return ResponseEntity.ok(body);
        }

        if (valid == null) {
            if (nextStart != null) {
                return denied("Entry to your exam has not opened yet. You can sign in from "
                        + nextStart.format(WHEN) + ".");
            }
            // Says which of the two it is, so the right person fixes the right
            // thing: the invigilator releases the paper, or checks the sitting.
            if (submittedAny) {
                return denied("You have already submitted this exam.");
            }
            if (sittingMissing) {
                return denied("There is no sitting of your exam left to sign in to. Ask your invigilator.");
            }
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

    /** How a time reads to a candidate: "12:00 PM, 24 Nov", not "2026-11-24T12:00". */
    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("h:mm a, d MMM", Locale.ENGLISH);

    /**
     * The name that goes with a hall ticket, so the candidate confirms who they
     * are instead of typing their name.
     *
     * Typed names were the commonest reason a real candidate could not get in:
     * an extra space, initials in a different order, a spelling that differs
     * from the college's records. Signing in is now by hall ticket alone; this
     * shows the name to confirm, and the sign-in that follows sends it back.
     *
     * A name is shown only when that candidate could sign in right now. The
     * lookup runs the whole of /validate -- the exam is released, the doors are
     * open, the paper is not already submitted -- and passes on its refusal
     * otherwise, so outside a candidate's own entry window this says no more
     * than signing in would. No token is issued here.
     */
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody Map<String, String> request) {
        String hallTicket = request.getOrDefault("hallTicket", "").trim();
        String institutionCode = request.getOrDefault("institutionCode", "").trim();
        if (hallTicket.isBlank()) {
            return denied("Enter your hall ticket number.");
        }

        List<com.Exam.Exam_System.Entity.Student> found;
        if (!institutionCode.isBlank()) {
            var institution = adminRepository.findByCodeIgnoreCase(institutionCode);
            if (institution.isEmpty()) {
                return denied("That exam link is not valid. Check with your invigilator.");
            }
            found = studentRepository.findByAdminIdAndHallTicketIgnoreCase(institution.get().getId(), hallTicket)
                    .stream().toList();
        } else {
            found = studentRepository.findByHallTicketIgnoreCase(hallTicket);
        }
        found = found.stream().filter(s -> !s.isDeleted()).toList();

        if (found.isEmpty()) {
            return denied("We could not find that hall ticket number. Check it, or ask your invigilator.");
        }
        if (found.size() > 1) {
            return denied("Several institutions have a candidate with this hall ticket. "
                    + "Please use your college's own exam link.");
        }
        var student = found.get(0);

        // Every gate signing in has, decided by signing in itself.
        Map<String, String> asSignIn = new HashMap<>(request);
        asSignIn.put("name", student.getName());
        ResponseEntity<?> outcome = validate(asSignIn);
        Object status = outcome.getBody() instanceof Map<?, ?> body ? body.get("status") : null;
        if (!"Allowed".equals(status) && !"Choose".equals(status)) {
            return outcome;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "Found");
        body.put("hallTicket", student.getHallTicket());
        body.put("name", student.getName());
        body.put("branch", student.getBranch());
        body.put("year", student.getYear());
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

        // Signing in early only admits a candidate to the briefing. The paper
        // opens at the sitting's start for everybody, whatever their own clock
        // or a hand-crafted request says.
        var slot = slotService.findSlot(mapping.getSlotId()).orElse(null);
        if (slot != null) {
            if (slot.getStartTime() != null && LocalDateTime.now().isBefore(slot.getStartTime())) {
                throw new IllegalStateException(
                        "The exam opens at " + slot.getStartTime().format(WHEN) + ". Please wait.");
            }
        }

        Attempt attempt = attemptService.startOrResume(studentId, examId, mapping.getSlotId());

        // Coming back after a dropped connection or a closed browser: give back
        // the time that cost before reading the clock. Read first, a deadline
        // that passed while they were away would submit the paper here, before
        // the time they were owed was ever returned.
        attemptService.markSeen(attempt.getId());

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

    /**
     * The clock, and the heartbeat that comes with it.
     *
     * Every live paper polls this every twenty seconds whatever the candidate
     * is doing, which makes it the one honest signal of "still there". The
     * monitor used to read liveness from the newest saved answer instead, so
     * anybody thinking about a hard question for two minutes was reported to
     * the invigilator as disconnected.
     */
    @GetMapping("/remaining/{attemptId}")
    public Map<String, Object> getRemaining(@PathVariable Long attemptId) {
        accessGuard.requireOwnAttempt(attemptId);

        attemptService.markSeen(attemptId);

        long remaining = attemptService.getRemainingSeconds(attemptId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remainingSeconds", remaining);
        data.put("expired", remaining == 0);

        /*
         * The sectional clock rides along with the paper's own.
         *
         * Advancing here rather than on a schedule means the boundary is
         * decided by the server but driven by the candidate's own poll — no
         * background job walking every live attempt, and a section closes
         * within twenty seconds of when it should. On an exam that is not
         * sectional every line below is a no-op.
         */
        Attempt attempt = attemptService.getById(attemptId);
        if (attempt.getCurrentSectionId() != null) {
            data.putAll(sectionClock.stateFor(sectionClock.advanceIfDue(attempt)));
        }
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

    /**
     * The candidate's own scorecard — once the college has announced results.
     *
     * Held back by default. A score shown the instant somebody presses submit
     * cannot be taken back: it is out around the hall before an invigilator has
     * looked at a single malpractice report, and a key corrected afterwards
     * means every candidate has already seen a mark that no longer stands.
     *
     * The withheld reply is a 200 carrying the submission, not an error, so the
     * candidate gets a page confirming their paper is in rather than something
     * that reads like their exam failed to save.
     */
    @GetMapping("/result/{attemptId}")
    public ResponseEntity<?> getResult(@PathVariable Long attemptId) {
        var attempt = accessGuard.requireOwnAttemptEntity(attemptId);

        var exam = examRepository.findById(attempt.getExamId()).orElse(null);
        if (exam == null || !exam.isResultsReleased()) {
            Map<String, Object> held = new LinkedHashMap<>();
            held.put("attemptId", attemptId);
            held.put("resultsReleased", false);
            held.put("submitted", "SUBMITTED".equals(attempt.getStatus()));
            held.put("submittedAt", attempt.getSubmittedAt());
            held.put("message", "Your paper has been received. "
                    + "Your college will announce the results.");
            return ResponseEntity.ok(held);
        }

        return ResponseEntity.ok(attemptService.getResult(attemptId));
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
        // The exam's own video if it has one, otherwise the college's.
        String introVideo = exam.getIntroVideo();
        if ((introVideo == null || introVideo.isBlank()) && exam.getAdminId() != null) {
            introVideo = adminRepository.findById(exam.getAdminId())
                    .map(a -> a.getIntroVideo()).orElse(null);
        }
        data.put("introVideo", introVideo);
        data.put("duration", exam.getDuration());
        data.put("enableCamera", exam.isEnableCamera());
        data.put("enableMic", exam.isEnableMic());

        // When the paper opens for this candidate, with the server's own clock
        // beside it. The lobby counts down on the difference between the two,
        // so a candidate's wrong system clock cannot open the paper early or
        // keep it shut.
        data.put("serverTime", LocalDateTime.now());
        data.put("opensAt", null);
        if (!currentUser.get().isAdmin()) {
            ExamStudent mapping = examStudentRepository
                    .findByStudentIdAndExamId(currentUser.studentId(), examId);
            if (mapping != null) {
                slotService.findSlot(mapping.getSlotId())
                        .ifPresent(slot -> data.put("opensAt", slot.getStartTime()));
            }
        }
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
