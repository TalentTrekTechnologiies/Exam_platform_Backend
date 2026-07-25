package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "attempts",
    // One attempt per student per exam. Without this, a double-tap on Start
    // created two attempts and the candidate's answers split across both.
    uniqueConstraints = @UniqueConstraint(
        name = "uk_attempt_student_exam",
        columnNames = {"studentId", "examId"}
    )
)
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studentId;

    private Long examId;

    private Long slotId;

    private LocalDateTime startTime;

    /**
     * The deadline — when this attempt's time runs out. Set once at start and
     * never changed, which is what makes it safe to cache and serve the clock
     * from memory instead of querying on every poll.
     */
    private LocalDateTime endTime;

    /** When the candidate actually finished. Null until submitted. */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    private String status; // STARTED, SUBMITTED

    private Double score;

    public Attempt() {}

    public Long getId() {
        return id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public Long getSlotId() {
        return slotId;
    }

    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
