package com.Exam.Exam_System.dto;

/** What a successful login returns. Deliberately carries no password field. */
public class AuthResponse {

    private final String token;
    private final Long id;
    private final String email;
    private final String code;
    private final String collegeName;
    private final String collegeAddress;
    private final String collegeLogo;
    private final String role;

    public AuthResponse(String token, Long id, String email, String code, String collegeName,
                        String collegeAddress, String collegeLogo, String role) {
        this.token = token;
        this.id = id;
        this.email = email;
        this.code = code;
        this.collegeName = collegeName;
        this.collegeAddress = collegeAddress;
        this.collegeLogo = collegeLogo;
        this.role = role;
    }

    public String getToken() { return token; }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    /** This institution's URL slug, e.g. "ksrm" — the candidate entrance. */
    public String getCode() { return code; }
    public String getCollegeName() { return collegeName; }
    public String getCollegeAddress() { return collegeAddress; }
    public String getCollegeLogo() { return collegeLogo; }
    public String getRole() { return role; }
}
