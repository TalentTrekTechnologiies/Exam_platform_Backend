package com.Exam.Exam_System.security;

/**
 * Who is making the request.
 *
 * `id` means adminId for ADMIN and studentId for STUDENT. `examId` is only set
 * for students and pins them to the exam they were validated for.
 *
 * `session` identifies one sign-in. A candidate legitimately signs in more than
 * once — a crashed machine, a power cut, moving to a spare PC — so a new
 * session on its own means nothing. What it makes visible is two sessions
 * writing to the same attempt at the same time, which is one hall ticket being
 * shared between two people. Null for admins, and for student tokens issued
 * before this existed.
 */
public record AuthPrincipal(Long id, Role role, String label, Long examId, String session) {

    public enum Role { ADMIN, STUDENT }

    public static AuthPrincipal admin(Long id, String email) {
        return new AuthPrincipal(id, Role.ADMIN, email, null, null);
    }

    public static AuthPrincipal student(Long id, String hallTicket, Long examId, String session) {
        return new AuthPrincipal(id, Role.STUDENT, hallTicket, examId, session);
    }

    public boolean isAdmin() { return role == Role.ADMIN; }
}
