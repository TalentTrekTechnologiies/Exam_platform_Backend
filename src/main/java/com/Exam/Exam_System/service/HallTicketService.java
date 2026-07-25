package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.ExamStudent;
import com.Exam.Exam_System.Entity.Student;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Issuing hall ticket numbers.
 *
 * Two ways in, because colleges genuinely differ. Some already hold roll numbers
 * from their own records and want to upload them; others just have a list of
 * names and want numbers issued. Both must land in the same place, and neither
 * may ever produce a duplicate — a hall ticket is how a candidate is identified
 * on exam day, and two people holding the same one is unrecoverable.
 */
@Service
public class HallTicketService {

    private static final Logger log = LoggerFactory.getLogger(HallTicketService.class);

    private final StudentRepository studentRepository;
    private final ExamStudentRepository examStudentRepository;
    private final StudentService studentService;

    public HallTicketService(StudentRepository studentRepository,
                             ExamStudentRepository examStudentRepository,
                             StudentService studentService) {
        this.studentRepository = studentRepository;
        this.examStudentRepository = examStudentRepository;
        this.studentService = studentService;
    }

    /** One issued candidate, as the admin screen displays it. */
    public record IssuedTicket(Long studentId, String hallTicket, String name, boolean newlyCreated) {}

    /** Outcome of a batch: what was issued, and what needs a human decision. */
    public record IssueReport(List<IssuedTicket> issued, List<String> skipped, String summary) {}

    /**
     * Issues hall tickets for a list of names.
     *
     * Numbers follow `prefix + zero-padded sequence`, e.g. 24CSE001, continuing
     * from the highest number already issued under that prefix so a second batch
     * never collides with the first. Existing candidates are matched by name and
     * keep the number they already have, which makes the whole operation safe to
     * re-run when a few more students turn up.
     */
    @Transactional
    public IssueReport issue(Long adminId, Long examId, Long slotId,
                             String prefix, int padding, List<String> names) {

        String cleanPrefix = prefix == null ? "" : prefix.trim().toUpperCase(Locale.ROOT);
        if (cleanPrefix.isBlank()) {
            throw new IllegalArgumentException("A hall ticket prefix is required, e.g. 24CSE.");
        }
        if (!cleanPrefix.matches("[A-Z0-9/-]{1,20}")) {
            throw new IllegalArgumentException(
                    "The prefix may use only letters, digits, hyphens and slashes.");
        }
        int width = Math.min(Math.max(padding, 1), 8);

        List<String> cleanNames = names == null ? List.of() : names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(n -> !n.isBlank())
                .toList();
        if (cleanNames.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one candidate name.");
        }

        int next = nextSequence(adminId, cleanPrefix);
        List<IssuedTicket> issued = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String name : cleanNames) {
            List<Student> sameName = studentRepository.findByAdminIdAndNameIgnoreCase(adminId, name);

            Student student;
            boolean created = false;

            if (sameName.size() > 1) {
                // Two candidates already share this name, so there is no way to
                // know which one this row means. Guessing could hand a stranger
                // another student's paper, so this stops and asks. Upload a CSV
                // with explicit hall tickets to resolve it.
                skipped.add(name + " — several candidates already have this name; "
                        + "upload a CSV with explicit hall tickets instead.");
                continue;
            }

            if (sameName.size() == 1) {
                // Already has a number. Keep it, so re-running after adding a few
                // more names never renumbers anyone.
                student = sameName.get(0);
            } else {
                String ticket;
                // Skip any number already taken — a prefix may have been used
                // before, with gaps, or by a manual upload.
                do {
                    ticket = cleanPrefix + String.format("%0" + width + "d", next++);
                } while (studentRepository.findByAdminIdAndHallTicketIgnoreCase(adminId, ticket).isPresent());

                student = studentService.saveOrGet(adminId, ticket, name);
                created = true;
            }

            if (examId != null && slotId != null
                    && !examStudentRepository.existsByStudentIdAndExamId(student.getId(), examId)) {
                ExamStudent mapping = new ExamStudent();
                mapping.setStudentId(student.getId());
                mapping.setExamId(examId);
                mapping.setSlotId(slotId);
                examStudentRepository.save(mapping);
            }

            issued.add(new IssuedTicket(student.getId(), student.getHallTicket(), student.getName(), created));
        }

        long fresh = issued.stream().filter(IssuedTicket::newlyCreated).count();
        String summary = "Issued " + fresh + " new hall ticket(s); "
                + (issued.size() - fresh) + " candidate(s) already had one"
                + (skipped.isEmpty() ? "." : "; " + skipped.size() + " need attention.");

        log.info("Hall tickets under prefix {} for institution {} — {}", cleanPrefix, adminId, summary);
        return new IssueReport(issued, skipped, summary);
    }

    /**
     * The next free number under a prefix.
     *
     * Derived from what has actually been issued rather than a counter, so it
     * stays correct even after manual uploads, deletions, or a restore.
     */
    private int nextSequence(Long adminId, String prefix) {
        int highest = 0;
        for (Student s : studentRepository.findByAdminIdAndHallTicketStartingWithIgnoreCase(adminId, prefix)) {
            String tail = s.getHallTicket().substring(Math.min(prefix.length(), s.getHallTicket().length()));
            if (tail.matches("\\d+")) {
                try {
                    highest = Math.max(highest, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // A number too long to be an int is not a sequence we issued.
                }
            }
        }
        return highest + 1;
    }
}
