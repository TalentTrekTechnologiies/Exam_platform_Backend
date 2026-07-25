package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Admin;
import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.Entity.ExamStudent;
import com.Exam.Exam_System.dto.AuthResponse;
import com.Exam.Exam_System.dto.UploadReport;
import com.Exam.Exam_System.repository.AdminRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.security.CurrentUser;
import com.Exam.Exam_System.security.JwtService;
import com.Exam.Exam_System.service.FileStorageService;
import com.Exam.Exam_System.service.HallTicketService;
import com.Exam.Exam_System.service.RosterImportService;
import com.Exam.Exam_System.service.StudentService;
import com.Exam.Exam_System.util.CsvParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminRepository adminRepository;
    private final ExamRepository examRepository;
    private final StudentService studentService;
    private final ExamStudentRepository examStudentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final AccessGuard accessGuard;
    private final FileStorageService fileStorage;
    private final HallTicketService hallTicketService;
    private final RosterImportService rosterImportService;

    /**
     * False for a dedicated single-institution install (KSRM's own module),
     * true for the shared platform where colleges sign themselves up.
     */
    @Value("${app.registration.enabled:true}")
    private boolean registrationEnabled;

    public AdminController(AdminRepository adminRepository,
                           ExamRepository examRepository,
                           StudentService studentService,
                           ExamStudentRepository examStudentRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           CurrentUser currentUser,
                           AccessGuard accessGuard,
                           FileStorageService fileStorage,
                           HallTicketService hallTicketService,
                           RosterImportService rosterImportService) {
        this.adminRepository = adminRepository;
        this.examRepository = examRepository;
        this.studentService = studentService;
        this.examStudentRepository = examStudentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.fileStorage = fileStorage;
        this.hallTicketService = hallTicketService;
        this.rosterImportService = rosterImportService;
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @PostMapping("/register")
    public AuthResponse register(@RequestParam String collegeName,
                                 @RequestParam String email,
                                 @RequestParam String password,
                                 @RequestParam(required = false) String collegeAddress,
                                 @RequestParam(required = false) MultipartFile logo) {

        // A single-institution deployment — KSRM running its own exam module —
        // has no notion of colleges signing themselves up. Closing this at the
        // API, not just hiding the link, is what actually prevents a stranger
        // creating an institution on their server.
        if (!registrationEnabled) {
            throw new AccessDeniedException("Registration is closed on this installation.");
        }

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (collegeName == null || collegeName.isBlank()) {
            throw new IllegalArgumentException("Institution name is required.");
        }
        if (adminRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException("An account with that email already exists.");
        }

        Admin admin = new Admin();
        admin.setCollegeName(collegeName.trim());
        admin.setCode(uniqueCodeFor(collegeName));
        admin.setEmail(normalizedEmail);
        // Hashed, not stored in the clear as it was previously.
        admin.setPassword(passwordEncoder.encode(password));
        admin.setCollegeAddress(collegeAddress);

        if (logo != null && !logo.isEmpty()) {
            admin.setCollegeLogo(fileStorage.storeImage(logo));
        }

        Admin saved = adminRepository.save(admin);
        return toAuthResponse(saved);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT);
        String password = request.getOrDefault("password", "");

        Admin admin = adminRepository.findByEmailIgnoreCase(email).orElse(null);

        // One message for both "no such account" and "wrong password" so the
        // endpoint can't be used to enumerate which institutions are registered.
        if (admin == null || !passwordEncoder.matches(password, admin.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "BAD_CREDENTIALS", "message", "Invalid email or password."));
        }

        return ResponseEntity.ok(toAuthResponse(admin));
    }

    /** The signed-in admin's own profile — used to rehydrate the UI after a refresh. */
    @GetMapping("/me")
    public AuthResponse me() {
        Admin admin = adminRepository.findById(currentUser.adminId())
                .orElseThrow(() -> new NoSuchElementException("Account not found"));
        return toAuthResponse(admin);
    }

    /**
     * Builds this institution's URL slug from its name, e.g.
     * "KSRM College of Engineering" -> "ksrm-college-of-engineering".
     *
     * A numeric suffix is appended if the slug is taken, so two colleges with
     * similar names still get distinct entrances.
     */
    private String uniqueCodeFor(String collegeName) {
        String base = collegeName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "institution";
        if (base.length() > 50) base = base.substring(0, 50).replaceAll("-$", "");

        String candidate = base;
        int suffix = 2;
        while (adminRepository.existsByCodeIgnoreCase(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private AuthResponse toAuthResponse(Admin admin) {
        return new AuthResponse(
                jwtService.issueAdminToken(admin.getId(), admin.getEmail()),
                admin.getId(),
                admin.getEmail(),
                admin.getCode(),
                admin.getCollegeName(),
                admin.getCollegeAddress(),
                admin.getCollegeLogo(),
                "ADMIN");
    }

    // ── Candidates ───────────────────────────────────────────────────────────

    /**
     * Bulk-assigns candidates to an exam slot.
     * Columns: hallTicket, name
     */
    @PostMapping("/students/upload")
    public UploadReport uploadStudents(@RequestParam("file") MultipartFile file,
                                       @RequestParam Long examId,
                                       @RequestParam Long slotId) {

        accessGuard.requireOwnedExam(examId);
        var slot = accessGuard.requireOwnedSlot(slotId);

        if (!slot.getExamId().equals(examId)) {
            throw new IllegalArgumentException("That slot belongs to a different exam.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }

        UploadReport report = new UploadReport();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                List<String> fields = CsvParser.parseLine(line);

                if (CsvParser.isBlank(fields)) continue;
                if (lineNumber == 1 && CsvParser.looksLikeHeader(fields)) continue;

                if (fields.size() < 2 || fields.get(0).isBlank() || fields.get(1).isBlank()) {
                    report.recordError(lineNumber, "Expected hallTicket,name.", line);
                    continue;
                }

                String hallTicket = fields.get(0);
                String name = fields.get(1);

                // Scoped to THIS institution: another college's candidate with the
                // same roll number must never be adopted into this roster.
                var student = studentService.saveOrGet(currentUser.adminId(), hallTicket, name);

                if (examStudentRepository.existsByStudentIdAndExamId(student.getId(), examId)) {
                    report.recordError(lineNumber, "Already assigned to this exam.", line);
                    continue;
                }

                ExamStudent mapping = new ExamStudent();
                mapping.setStudentId(student.getId());
                mapping.setExamId(examId);
                mapping.setSlotId(slotId);
                examStudentRepository.save(mapping);
                report.recordSaved();
            }

        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read the file: " + e.getMessage());
        }

        return report;
    }

    /**
     * Reads a candidate roster from CSV, Excel, Word or PDF — WITHOUT saving.
     *
     * Colleges keep rosters in Excel far more often than CSV, and converting by
     * hand is where mistyped hall tickets come from. The preview is what makes
     * accepting messier formats safe: duplicates, blank names and anything that
     * does not look like a hall ticket are flagged for a human before enrolment.
     */
    @PostMapping("/students/import/preview")
    public RosterImportService.RosterPreview previewRoster(@RequestParam("file") MultipartFile file,
                                                           @RequestParam Long examId) {
        accessGuard.requireOwnedExam(examId);
        return rosterImportService.parse(file);
    }

    /** Enrols the rows an admin reviewed and corrected. */
    @PostMapping("/students/import/confirm")
    public UploadReport confirmRoster(@RequestBody Map<String, Object> request) {
        Long examId = Long.valueOf(String.valueOf(request.get("examId")));
        Long slotId = Long.valueOf(String.valueOf(request.get("slotId")));

        accessGuard.requireOwnedExam(examId);
        var slot = accessGuard.requireOwnedSlot(slotId);
        if (!slot.getExamId().equals(examId)) {
            throw new IllegalArgumentException("That slot belongs to a different exam.");
        }

        if (!(request.get("candidates") instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalArgumentException("No candidates were submitted.");
        }

        UploadReport report = new UploadReport();
        int line = 0;
        for (Object row : rows) {
            line++;
            if (!(row instanceof Map<?, ?> map)) {
                report.recordError(line, "Malformed row.", "");
                continue;
            }
            String hallTicket = text(map.get("hallTicket"));
            String name = text(map.get("name"));

            if (hallTicket.isBlank() || name.isBlank()) {
                report.recordError(line, "Both a hall ticket and a name are required.", hallTicket);
                continue;
            }

            var student = studentService.saveOrGet(currentUser.adminId(), hallTicket, name);
            if (examStudentRepository.existsByStudentIdAndExamId(student.getId(), examId)) {
                report.recordError(line, "Already enrolled in this exam.", hallTicket);
                continue;
            }

            ExamStudent mapping = new ExamStudent();
            mapping.setStudentId(student.getId());
            mapping.setExamId(examId);
            mapping.setSlotId(slotId);
            examStudentRepository.save(mapping);
            report.recordSaved();
        }
        return report;
    }

    /** Null-safe read of a value from a loosely-typed request map. */
    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Issues hall tickets for a list of names.
     *
     * The alternative to uploading a CSV: a college that has names but no roll
     * numbers gets them generated as `prefix + sequence`, continuing from
     * whatever has already been issued so batches never collide. Both routes
     * remain available because colleges genuinely work both ways.
     */
    @PostMapping("/students/issue-hall-tickets")
    public HallTicketService.IssueReport issueHallTickets(@RequestBody Map<String, Object> request) {
        Long examId = request.get("examId") == null ? null : Long.valueOf(request.get("examId").toString());
        Long slotId = request.get("slotId") == null ? null : Long.valueOf(request.get("slotId").toString());

        if (examId != null) {
            accessGuard.requireOwnedExam(examId);
            if (slotId != null) {
                var slot = accessGuard.requireOwnedSlot(slotId);
                if (!slot.getExamId().equals(examId)) {
                    throw new IllegalArgumentException("That slot belongs to a different exam.");
                }
            }
        }

        Object rawNames = request.get("names");
        List<String> names = new ArrayList<>();
        if (rawNames instanceof List<?> list) {
            list.forEach(n -> { if (n != null) names.add(n.toString()); });
        } else if (rawNames != null) {
            // Accept a pasted block of names, one per line — how an exam officer
            // most naturally has them to hand.
            for (String line : rawNames.toString().split("\\r?\\n")) names.add(line);
        }

        String prefix = String.valueOf(request.getOrDefault("prefix", ""));
        int padding = request.get("padding") == null ? 3 : Integer.parseInt(request.get("padding").toString());

        return hallTicketService.issue(currentUser.adminId(), examId, slotId, prefix, padding, names);
    }

    /** Candidates for this admin's exams only. */
    @GetMapping("/students")
    public List<Map<String, Object>> getStudents(@RequestParam(required = false) Long examId) {
        return studentRows(examId);
    }

    /** Dashboard headline count — counted in the database, not by loading rows. */
    @GetMapping("/students/count")
    public Map<String, Object> countStudents(@RequestParam(required = false) Long examId) {
        if (examId != null) accessGuard.requireOwnedExam(examId);
        return Map.of("count", examStudentRepository.countRosterForAdmin(currentUser.adminId(), examId));
    }

    /** Candidate list as CSV, for offline attendance sheets. */
    @GetMapping(value = "/students/export", produces = "text/csv")
    public ResponseEntity<String> exportStudents(@RequestParam(required = false) Long examId) {
        StringBuilder csv = new StringBuilder("hallTicket,name,examId,slotId\n");

        for (Map<String, Object> row : studentRows(examId)) {
            csv.append(quote(row.get("hallTicket"))).append(',')
               .append(quote(row.get("name"))).append(',')
               .append(row.get("examId")).append(',')
               .append(row.get("slotId")).append('\n');
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"candidates.csv\"")
                .body(csv.toString());
    }

    /** Names can contain commas, so every text cell is quoted on the way out. */
    private String quote(Object value) {
        String text = value == null ? "" : value.toString();
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * The candidate roster, fetched with one joined query scoped to this
     * institution.
     *
     * This previously loaded every ExamStudent row in the database, filtered them
     * in memory, then issued a separate query per candidate to get their name —
     * a full table scan plus N round trips to render one college's list.
     */
    private List<Map<String, Object>> studentRows(Long examId) {
        if (examId != null) accessGuard.requireOwnedExam(examId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : examStudentRepository.findRosterForAdmin(currentUser.adminId(), examId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("examId", r[1]);
            row.put("slotId", r[2]);
            row.put("studentId", r[3]);
            row.put("hallTicket", r[4]);
            row.put("name", r[5]);
            out.add(row);
        }
        return out;
    }
}
