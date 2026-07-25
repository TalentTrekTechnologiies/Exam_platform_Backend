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
                    .findByAdminIdAndHallTicketIgnoreCaseAndNameIgnoreCase(adminId, ticket, candidateName);
        }

        List<Student> matches =
                studentRepository.findByHallTicketIgnoreCaseAndNameIgnoreCase(ticket, candidateName);

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
     */
    @Transactional
    public Student saveOrGet(Long adminId, String hallTicket, String name) {
        String ticket = hallTicket.trim();

        return studentRepository.findByAdminIdAndHallTicketIgnoreCase(adminId, ticket)
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
