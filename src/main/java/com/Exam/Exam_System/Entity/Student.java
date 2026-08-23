package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

/**
 * A candidate, belonging to exactly one institution.
 *
 * The hall ticket is unique WITHIN an institution, not across the platform.
 * That distinction is load-bearing: roll numbers like 24CSE001 repeat across
 * colleges constantly, and a global constraint meant the second college to
 * enrol that number silently inherited the first college's student record —
 * leaking one institution's candidate into another's roster and locking the
 * real candidate out of their own exam.
 */
@Entity
@Table(
    name = "students",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_student_institution_ticket",
        columnNames = {"admin_id", "hall_ticket"}
    )
)
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The owning institution. */
    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "hall_ticket", nullable = false)
    private String hallTicket;

    @Column(nullable = false)
    private String name;

    /**
     * Where the hall ticket is sent.
     *
     * Nullable on purpose: candidates enrolled from a roster upload often have
     * no email at all, and requiring one would break the flow that already
     * works. Only self-registered candidates are guaranteed to have one.
     */
    @Column(length = 320)
    private String email;

    /** Optional, collected at self-registration. Useful when an email bounces. */
    @Column(length = 20)
    private String phone;

    /**
     * When this candidate was taken off the college's books, or null.
     *
     * Removed rather than deleted, because the results are not the candidate's
     * row to take with them. Every report reads the hall ticket and name from
     * here, so a real DELETE would erase the marks of every exam this person
     * ever sat — an admin tidying a roster would silently destroy the record of
     * a completed sitting. Removed candidates drop off the student list and can
     * no longer sign in; every mark they earned stays exactly where it was.
     */
    @Column(name = "deleted_at")
    private java.time.LocalDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public java.time.LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(java.time.LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** True once this candidate has been taken off the books. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getHallTicket() {
        return hallTicket;
    }

    public void setHallTicket(String hallTicket) {
        this.hallTicket = hallTicket;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
