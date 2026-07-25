package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A public sign-up form for one exam.
 *
 * Candidates reach it by an unguessable token rather than the exam id, so the
 * link can be shared in a WhatsApp group without also handing out a way to
 * enumerate every other exam on the server.
 *
 * The open/close window lives here and is enforced on the server. Hiding the
 * form in the UI after it closes would stop nobody: the submit endpoint checks
 * the clock itself on every request.
 */
@Entity
@Table(
    name = "registration_forms",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_regform_token", columnNames = "token"),
        // One form per exam. Two live forms for the same exam would split the
        // candidate list in half with no way to tell which was authoritative.
        @UniqueConstraint(name = "uk_regform_exam", columnNames = "exam_id")
    }
)
public class RegistrationForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    /** Owning institution, so an admin can never open another college's form. */
    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "opens_at", nullable = false)
    private LocalDateTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalDateTime closesAt;

    /**
     * Lets staff close the form early without waiting for the clock — the usual
     * reason being that enough candidates have registered.
     */
    @Column(name = "closed_early", nullable = false)
    private boolean closedEarly = false;

    /** Shown above the form. Free text, e.g. joining instructions. */
    @Column(length = 2000)
    private String instructions;

    /**
     * Optional email domain restriction, e.g. "ksrm.edu.in". Empty means any
     * address is accepted — the normal case when candidates use personal email.
     */
    @Column(name = "email_domain", length = 255)
    private String emailDomain;

    /**
     * Refuses further submissions once this many have arrived. A public form on
     * an open link is otherwise unbounded, and a script could fill the roster
     * with thousands of junk entries in seconds.
     */
    @Column(name = "max_registrations")
    private Integer maxRegistrations;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Open only inside the window, and only if staff haven't closed it early. */
    @Transient
    public boolean isOpenAt(LocalDateTime now) {
        return !closedEarly && now.isAfter(opensAt) && now.isBefore(closesAt);
    }

    public Long getId() { return id; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public LocalDateTime getOpensAt() { return opensAt; }
    public void setOpensAt(LocalDateTime opensAt) { this.opensAt = opensAt; }

    public LocalDateTime getClosesAt() { return closesAt; }
    public void setClosesAt(LocalDateTime closesAt) { this.closesAt = closesAt; }

    public boolean isClosedEarly() { return closedEarly; }
    public void setClosedEarly(boolean closedEarly) { this.closedEarly = closedEarly; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public String getEmailDomain() { return emailDomain; }
    public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }

    public Integer getMaxRegistrations() { return maxRegistrations; }
    public void setMaxRegistrations(Integer maxRegistrations) { this.maxRegistrations = maxRegistrations; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
