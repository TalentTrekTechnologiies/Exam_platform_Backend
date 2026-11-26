package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Student;
import com.Exam.Exam_System.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    /** Raised when one hall ticket + name matches candidates at several institutions. */
    public static class AmbiguousCandidateException extends RuntimeException {
        public AmbiguousCandidateException(String message) { super(message); }
    }

    /**
     * Finds a candidate for sign-in.
     *
     * When an institution is known — the normal case, because each college has
     * its own entrance URL — the search is scoped to it and identical roll
     * numbers at other colleges are irrelevant.
     *
     * When it is not known, the search falls back to the whole platform, and
     * refuses rather than guesses if more than one institution has a candidate
     * with that hall ticket and name.
     */
    @Transactional(readOnly = true)
    public Optional<Student> findCandidate(Long adminId, String hallTicket, String name) {
        if (hallTicket == null || name == null) return Optional.empty();
        String ticket = hallTicket.trim();
        String candidateName = name.trim();

        if (adminId != null) {
            return studentRepository
                    .findByAdminIdAndHallTicketIgnoreCaseAndNameIgnoreCase(adminId, ticket, candidateName)
                    // A candidate taken off the books keeps their results but
                    // loses their way in.
                    .filter(s -> !s.isDeleted());
        }

        List<Student> matches =
                studentRepository.findByHallTicketIgnoreCaseAndNameIgnoreCase(ticket, candidateName)
                        .stream().filter(s -> !s.isDeleted()).toList();

        if (matches.size() > 1) {
            throw new AmbiguousCandidateException(
                    "Several institutions have a candidate with these details. "
                            + "Please use your college's own exam link.");
        }
        return matches.stream().findFirst();
    }

    /**
     * Finds or creates a candidate during enrolment, within one institution.
     *
     * The institution scope is what stops a college's upload from silently
     * adopting another college's student who happens to share a roll number.
     *
     * A candidate who was previously removed is brought back rather than
     * inserted afresh: the hall ticket is unique per institution, so a second
     * row could not be written anyway, and reviving the original keeps every
     * result already sitting against it.
     */
    @Transactional
    public Student saveOrGet(Long adminId, String hallTicket, String name) {
        return saveOrGet(adminId, hallTicket, name, null, null);
    }

    /**
     * As above, also recording branch and year when the roster carries them.
     * A blank value leaves what is stored alone, so a later upload without
     * those columns does not wipe them; a filled one corrects them.
     */
    @Transactional
    public Student saveOrGet(Long adminId, String hallTicket, String name, String branch, String year) {
        Student student = saveOrGetCore(adminId, hallTicket, name);
        boolean changed = false;
        if (branch != null && !branch.isBlank() && !branch.trim().equals(student.getBranch())) {
            student.setBranch(trimTo(branch, 60));
            changed = true;
        }
        if (year != null && !year.isBlank() && !year.trim().equals(student.getYear())) {
            student.setYear(trimTo(year, 20));
            changed = true;
        }
        return changed ? studentRepository.save(student) : student;
    }

    private static String trimTo(String value, int max) {
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Student saveOrGetCore(Long adminId, String hallTicket, String name) {
        String ticket = hallTicket.trim();

        return studentRepository.findByAdminIdAndHallTicketIgnoreCase(adminId, ticket)
                .map(existing -> {
                    if (existing.isDeleted()) {
                        existing.setDeletedAt(null);
                        // Enrolling under a new spelling is how the name gets
                        // corrected; the old one was why they were removed.
                        existing.setName(name.trim());
                        return studentRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Student s = new Student();
                    s.setAdminId(adminId);
                    s.setHallTicket(ticket);
                    s.setName(name.trim());
                    return studentRepository.save(s);
                });
    }

    @Transactional(readOnly = true)
    public Student getById(Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));
    }
}
