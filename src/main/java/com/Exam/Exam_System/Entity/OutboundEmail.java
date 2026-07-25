package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One queued email.
 *
 * Mail is never sent from a request thread. At 2,000+ candidates a loop over
 * send() would take minutes, hit the provider's rate limit part way through,
 * and leave nobody able to say which candidates got their hall ticket and which
 * did not — and that email is the candidate's only credential.
 *
 * So every message becomes a row first. A paced worker drains the table, failures
 * are retried with backoff, and the exam officer gets a screen that answers the
 * only question that matters on the morning of the exam: who hasn't received
 * theirs yet.
 */
@Entity
@Table(
    name = "outbound_emails",
    indexes = {
        @Index(name = "idx_outbox_status_next", columnList = "status,next_attempt_at"),
        @Index(name = "idx_outbox_exam", columnList = "exam_id")
    }
)
public class OutboundEmail {

    public static final String QUEUED = "QUEUED";
    /** Claimed by one instance and in flight. See MailService for why. */
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    /** Beyond this a message stops being retried and waits for a human. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "exam_id")
    private Long examId;

    /** Which candidate this is for, so the UI can name who is still waiting. */
    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "to_address", nullable = false, length = 320)
    private String toAddress;

    @Column(nullable = false, length = 500)
    private String subject;

    @Lob
    @Column(name = "body_html", nullable = false)
    private String bodyHtml;

    /**
     * Plain-text alternative. Sent alongside the HTML because some institutional
     * mail clients strip HTML entirely, and a hall ticket that arrives as a blank
     * message is worse than no message at all.
     */
    @Lob
    @Column(name = "body_text")
    private String bodyText;

    @Column(nullable = false, length = 16)
    private String status = QUEUED;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /**
     * Which app instance is currently sending this.
     *
     * The exam-day stack runs three instances behind nginx, so three schedulers
     * drain this table at once. Without a claim, all three would pick up the
     * same rows and a candidate would receive their hall ticket three times.
     */
    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public Long getId() { return id; }

    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }

    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }

    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
}
