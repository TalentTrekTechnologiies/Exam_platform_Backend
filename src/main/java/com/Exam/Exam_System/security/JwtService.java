package com.Exam.Exam_System.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration adminTtl;
    private final Duration studentTtl;

    /** The documented development fallback. Refused outside development. */
    static final String DEV_SECRET = "dev-only-secret-change-me-in-production-32ch";

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.admin-ttl-hours:8}") long adminTtlHours,
            @Value("${app.jwt.student-ttl-hours:6}") long studentTtlHours,
            org.springframework.core.env.Environment environment) {

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters. Set the JWT_SECRET environment variable.");
        }

        // Anyone holding this value can mint admin tokens for every institution on
        // the server. Refusing to boot is the only safe response: a warning in a
        // log is too easy to miss, and the failure is silent until it is abused.
        boolean devProfile = environment.matchesProfiles("dev", "test", "default");
        if (DEV_SECRET.equals(secret) && !devProfile) {
            throw new IllegalStateException("""

                    ─────────────────────────────────────────────────────────────
                     REFUSING TO START: the JWT secret is still the dev default.
                     Anyone with this value can issue admin tokens for EVERY
                     institution on this server.

                     Set a real one:  JWT_SECRET=$(openssl rand -base64 48)
                    ─────────────────────────────────────────────────────────────""");
        }
        if (DEV_SECRET.equals(secret)) {
            log.warn("Using the DEVELOPMENT JWT secret. Never deploy with this.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.adminTtl = Duration.ofHours(adminTtlHours);
        // Student tokens outlive the longest paper but not by much — a leaked
        // token shouldn't stay useful after the exam window closes.
        this.studentTtl = Duration.ofHours(studentTtlHours);
    }

    public String issueAdminToken(Long adminId, String email) {
        return build(adminId, AuthPrincipal.Role.ADMIN, email, null, adminTtl);
    }

    public String issueStudentToken(Long studentId, String hallTicket, Long examId) {
        return build(studentId, AuthPrincipal.Role.STUDENT, hallTicket, examId, studentTtl);
    }

    private String build(Long id, AuthPrincipal.Role role, String label, Long examId, Duration ttl) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(id))
                .claim("role", role.name())
                .claim("label", label)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()));

        if (examId != null) builder.claim("examId", examId);
        return builder.signWith(key).compact();
    }

    /** Returns null for anything not a currently-valid token. */
    public AuthPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            AuthPrincipal.Role role = AuthPrincipal.Role.valueOf(claims.get("role", String.class));
            Long id = Long.valueOf(claims.getSubject());
            String label = claims.get("label", String.class);
            Number examId = claims.get("examId", Number.class);

            return new AuthPrincipal(id, role, label, examId == null ? null : examId.longValue());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected token: {}", e.getMessage());
            return null;
        }
    }
}
