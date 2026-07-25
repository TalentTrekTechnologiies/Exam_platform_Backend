package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.OutboundEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmail, Long> {

    /**
     * Claims a batch for this instance, atomically.
     *
     * A plain SELECT-then-send would double-send on the multi-instance stack:
     * three schedulers reading the same QUEUED rows all send them. A single
     * UPDATE is atomic in MySQL, so exactly one instance wins each row.
     *
     * Native because JPQL has no LIMIT on bulk updates.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE outbound_emails
               SET status = 'SENDING', claimed_by = :owner, claimed_at = NOW()
             WHERE status = 'QUEUED' AND next_attempt_at <= NOW()
             ORDER BY id
             LIMIT :limit
            """, nativeQuery = true)
    int claimBatch(@Param("owner") String owner, @Param("limit") int limit);

    List<OutboundEmail> findByStatusAndClaimedByOrderByIdAsc(String status, String claimedBy);

    /**
     * Returns rows stranded by an instance that died mid-send.
     *
     * Without this a crash during a bulk send would leave candidates permanently
     * un-emailed, with the rows sitting in SENDING and no scheduler touching them.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboundEmail e
               SET e.status = 'QUEUED', e.claimedBy = NULL, e.claimedAt = NULL
             WHERE e.status = 'SENDING' AND e.claimedAt < :cutoff
            """)
    int releaseStranded(@Param("cutoff") LocalDateTime cutoff);

    long countByExamIdAndStatus(Long examId, String status);

    List<OutboundEmail> findByExamIdAndStatusOrderByIdAsc(Long examId, String status);

    long countByStatus(String status);

    long countByExamId(Long examId);
}
