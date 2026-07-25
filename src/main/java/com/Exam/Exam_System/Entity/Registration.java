package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One candidate's submission to a public registration form.
 *
 * Deliberately NOT a Student. A submission is an unverified claim by whoever
 * had the link; a Student is an enrolled candidate holding a hall ticket. Staff
 * approve the crossing between the two, which is the point at which duplicates
 * and junk entries get caught — before they become credentials.
 */
@Entity
@Table(
    name = "registrations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_registration_form_email",
        columnNames = {"form_id", "email"}
    ),
    indexes = @Index(name = "idx_registration_form_status", columnList = "form_id,status")
)
public class Registration {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_id", nullable = false)
    private Long formId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(length = 20)
    private String phone;

    /**
     * What the candidate says their roll number is. Only a claim until staff
     * approve it — and when it's blank, the hall ticket is generated instead.
     */
    @Column(name = "roll_number", length = 64)
    private String rollNumber;

    @Column(length = 120)
    private String branch;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    /** Set once approved and turned into a candidate. */
    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "hall_ticket", length = 64)
    private String hallTicket;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** Why staff rejected it, so the decision is auditable afterwards. */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    public Long getId() { return id; }

    public Long getFormId() { return formId; }
    public void setFormId(Long formId) { this.formId = formId; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRollNumber() { return rollNumber; }
    public void setRollNumber(String rollNumber) { this.rollNumber = rollNumber; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getHallTicket() { return hallTicket; }
    public void setHallTicket(String hallTicket) { this.hallTicket = hallTicket; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
