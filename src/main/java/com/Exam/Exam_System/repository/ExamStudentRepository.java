package com.Exam.Exam_System.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
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

    /** How many candidates are enrolled in one sitting. */
    long countBySlotId(Long slotId);

    /** Moves an exam's candidates whose sitting no longer exists into this one. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE exam_student es
              LEFT JOIN exam_slots sl ON sl.id = es.slot_id
               SET es.slot_id = :slotId
             WHERE es.exam_id = :examId
               AND es.slot_id IS NOT NULL
               AND sl.id IS NULL
            """, nativeQuery = true)
    int adoptStranded(@Param("examId") Long examId, @Param("slotId") Long slotId);

    /**
     * Enrols a candidate unless they are already in the exam, in one statement.
     *
     * Returns 1 when enrolled, 0 when they were already there. Checking first
     * and then saving let two overlapping requests (a slow line, Add pressed
     * again) both pass the check and both insert; the unique key on
     * (student_id, exam_id) settles it inside the database instead. IGNORE is
     * safe here because every value is validated before this is called.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO exam_student (student_id, exam_id, slot_id) VALUES (:studentId, :examId, :slotId)",
           nativeQuery = true)
    int enrolIfAbsent(@Param("studentId") Long studentId,
                      @Param("examId") Long examId,
                      @Param("slotId") Long slotId);

    ExamStudent findByStudentIdAndExamId(Long studentId, Long examId);

    // Used to validate student belongs to this specific slot
    Optional<ExamStudent> findByStudentIdAndSlotId(
            Long studentId, Long slotId);
}