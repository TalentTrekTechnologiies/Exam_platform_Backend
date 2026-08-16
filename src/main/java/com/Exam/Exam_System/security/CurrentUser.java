package com.Exam.Exam_System.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the authenticated principal out of the security context. */
@Component
public class CurrentUser {

    public AuthPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return principal;
    }

    public Long adminId() {
        AuthPrincipal principal = get();
        if (!principal.isAdmin()) throw new AccessDeniedException("Admin access required.");
        return principal.id();
    }

    public Long studentId() {
        AuthPrincipal principal = get();
        if (principal.isAdmin()) throw new AccessDeniedException("Student access required.");
        return principal.id();
    }

    /** Which sign-in this request belongs to. Null on tokens issued before sessions existed. */
    public String session() {
        return get().session();
    }
}
