package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    /**
     * Sign-in lookup, scoped to one institution. Case- and whitespace-insensitive
     * on both fields, because candidates type their own name inconsistently.
     */
    Optional<Student> findByAdminIdAndHallTicketIgnoreCaseAndNameIgnoreCase(
            Long adminId, String hallTicket, String name);

    /** Enrolment lookup within an institution. */
    Optional<Student> findByAdminIdAndHallTicketIgnoreCase(Long adminId, String hallTicket);

    /** Everyone already issued a ticket under a prefix, to continue the sequence. */
    List<Student> findByAdminIdAndHallTicketStartingWithIgnoreCase(Long adminId, String prefix);

    /**
     * Candidates with this name at this institution.
     *
     * A list, not a single row, because names are emphatically not unique — and
     * issuing a hall ticket to the wrong person because two students share a
     * name is precisely the mistake worth refusing to make.
     */
    List<Student> findByAdminIdAndNameIgnoreCase(Long adminId, String name);

    /**
     * Platform-wide match on hall ticket + name.
     *
     * Used only when no institution was supplied, i.e. a single-college
     * deployment. Deliberately returns a list rather than one row so that an
     * ambiguous match across institutions is detected and refused, never guessed.
     */
    List<Student> findByHallTicketIgnoreCaseAndNameIgnoreCase(String hallTicket, String name);
}
