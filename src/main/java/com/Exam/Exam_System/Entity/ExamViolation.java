package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A recorded proctoring event: leaving fullscreen, switching tabs, moving to
 * another application.
 *
 * These were previously detected in the browser and kept only in local storage,
 * which meant an invigilator had no evidence after the fact and a candidate
 * could erase the record by clearing their browser. Persisting them server-side
 * is what makes an integrity claim defensible.
 */
@Entity
@Table(
    name = "exam_violations",
    indexes = {
        @Index(name = "idx_violation_attempt", columnList = "attempt_id"),
        @Index(name = "idx_violation_exam", columnList = "exam_id")
    }
)
public class ExamViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    /** FULLSCREEN_EXIT, TAB_SWITCH, APP_SWITCH, AUTO_SUBMIT. */
    @Column(name = "violation_type", nullable = false, length = 40)
    private String type;

    /** The candidate's running count at the moment this was recorded. */
    @Column(name = "occurrence")
    private Integer occurrence;

    @Column(name = "detail", length = 300)
    private String detail;

    /** Server time, not the candidate's clock — their machine is not trusted. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public ExamViolation() {}

    public Long getId() { return id; }

    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getOccurrence() { return occurrence; }
    public void setOccurrence(Integer occurrence) { this.occurrence = occurrence; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
