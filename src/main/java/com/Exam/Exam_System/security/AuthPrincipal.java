package com.Exam.Exam_System.security;

/**
 * Who is making the request.
 *
 * `id` means adminId for ADMIN and studentId for STUDENT. `examId` is only set
 * for students and pins them to the exam they were validated for.
 */
public record AuthPrincipal(Long id, Role role, String label, Long examId) {

    public enum Role { ADMIN, STUDENT }

    public static AuthPrincipal admin(Long id, String email) {
        return new AuthPrincipal(id, Role.ADMIN, email, null);
    }

    public static AuthPrincipal student(Long id, String hallTicket, Long examId) {
        return new AuthPrincipal(id, Role.STUDENT, hallTicket, examId);
    }

    public boolean isAdmin() { return role == Role.ADMIN; }
}
