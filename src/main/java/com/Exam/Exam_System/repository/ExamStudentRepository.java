package com.Exam.Exam_System.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.Exam.Exam_System.Entity.ExamStudent;
import java.util.List;
import java.util.Optional;

public interface ExamStudentRepository
        extends JpaRepository<ExamStudent, Long> {

    List<ExamStudent> findByStudentId(Long studentId);

    /** Every candidate assigned to an exam — used to pre-build papers ahead of time. */
    List<ExamStudent> findByExamId(Long examId);

    /**
     * Candidate roster for one institution, joined in the database rather than
     * pulled row by row.
     *
     * The listing used to call findAll() and filter in memory, then fetch each
     * student individually — every institution's mappings loaded into heap to
     * show one college's list. At 100,000 candidates that is unusable.
     */
    @Query("""
            SELECT es.id, es.examId, es.slotId, s.id, s.hallTicket, s.name
              FROM ExamStudent es
              JOIN Student s ON s.id = es.studentId
              JOIN Exam e    ON e.id = es.examId
             WHERE e.adminId = :adminId
               AND (:examId IS NULL OR es.examId = :examId)
             ORDER BY s.hallTicket
            """)
    List<Object[]> findRosterForAdmin(@Param("adminId") Long adminId, @Param("examId") Long examId);

    @Query("""
            SELECT COUNT(es)
              FROM ExamStudent es
              JOIN Exam e ON e.id = es.examId
             WHERE e.adminId = :adminId
               AND (:examId IS NULL OR es.examId = :examId)
            """)
    long countRosterForAdmin(@Param("adminId") Long adminId, @Param("examId") Long examId);

    boolean existsByStudentIdAndExamId(Long studentId, Long examId);

    ExamStudent findByStudentIdAndExamId(Long studentId, Long examId);

    // Used to validate student belongs to this specific slot
    Optional<ExamStudent> findByStudentIdAndSlotId(
            Long studentId, Long slotId);
}