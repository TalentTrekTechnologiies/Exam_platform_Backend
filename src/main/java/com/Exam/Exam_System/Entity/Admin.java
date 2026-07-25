package com.Exam.Exam_System.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * An institution's account. Also the tenant boundary: everything an admin
 * creates (exams, sections, questions, students) hangs off this id.
 */
@Entity
@Table(
    name = "admin",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_admin_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_admin_code", columnNames = "code")
    }
)
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * URL-safe identifier for this institution, e.g. "ksrm".
     *
     * This is what lets each college have its own entrance — exams.ksrm.edu, or
     * /ksrm on a shared host — so a candidate types only their hall ticket and
     * name, and two colleges can both number a student 24CSE001 without
     * colliding.
     */
    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false)
    private String collegeName;

    @Column(nullable = false)
    private String email;

    /**
     * BCrypt hash. Never serialised — the login endpoint used to return the whole
     * Admin entity, plaintext password included, straight to the browser.
     */
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    private String collegeAddress;
    private String collegeLogo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String collegeName) { this.collegeName = collegeName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getCollegeAddress() { return collegeAddress; }
    public void setCollegeAddress(String collegeAddress) { this.collegeAddress = collegeAddress; }

    public String getCollegeLogo() { return collegeLogo; }
    public void setCollegeLogo(String collegeLogo) { this.collegeLogo = collegeLogo; }
}
