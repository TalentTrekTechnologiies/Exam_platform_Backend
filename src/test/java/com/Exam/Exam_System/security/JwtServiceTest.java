package com.Exam.Exam_System.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tokens are the whole access-control story, so these cover the properties that
 * would matter if they were wrong: identity, role separation, tamper rejection,
 * and refusing to run production on the development secret.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-long-enough-to-satisfy-hmac-sha";

    private JwtService service(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new JwtService(SECRET, 8, 6, env);
    }

    @Test
    @DisplayName("an admin token round-trips with its id and role")
    void adminRoundTrip() {
        JwtService jwt = service("test");
        AuthPrincipal p = jwt.parse(jwt.issueAdminToken(42L, "principal@college.edu"));

        assertNotNull(p);
        assertEquals(42L, p.id());
        assertTrue(p.isAdmin());
        assertEquals("principal@college.edu", p.label());
        assertNull(p.examId(), "an admin is not scoped to one exam");
    }

    @Test
    @DisplayName("a student token carries the exam it was issued for")
    void studentRoundTrip() {
        JwtService jwt = service("test");
        AuthPrincipal p = jwt.parse(jwt.issueStudentToken(7L, "24EAM001", 99L));

        assertNotNull(p);
        assertEquals(7L, p.id());
        assertFalse(p.isAdmin());
        assertEquals(99L, p.examId(), "pins the candidate to one exam");
    }

    @Test
    @DisplayName("a tampered token is rejected, not silently trusted")
    void rejectsTampering() {
        JwtService jwt = service("test");
        String token = jwt.issueAdminToken(1L, "a@b.edu");

        // Flip a character in the payload; the signature must no longer verify.
        String[] parts = token.split("\\.");
        String mangled = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XY." + parts[2];

        assertNull(jwt.parse(mangled));
    }

    @Test
    @DisplayName("garbage and empty tokens are rejected without throwing")
    void rejectsGarbage() {
        JwtService jwt = service("test");
        assertNull(jwt.parse("not-a-token"));
        assertNull(jwt.parse(""));
        assertNull(jwt.parse("a.b.c"));
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsForeignSignature() {
        JwtService mine = service("test");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        JwtService theirs = new JwtService("a-completely-different-secret-of-sufficient-len", 8, 6, env);

        assertNull(mine.parse(theirs.issueAdminToken(1L, "x@y.edu")),
                "a token minted elsewhere must not be accepted here");
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpired() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        // Zero-hour TTL: the token is already past its expiry when issued.
        JwtService jwt = new JwtService(SECRET, 0, 0, env);
        assertNull(jwt.parse(jwt.issueAdminToken(1L, "x@y.edu")));
    }

    @Test
    @DisplayName("a short secret is refused outright")
    void refusesWeakSecret() {
        MockEnvironment env = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 8, 6, env));
    }

    @Test
    @DisplayName("the dev secret is allowed in development but refused in production")
    void refusesDevSecretInProduction() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new JwtService(JwtService.DEV_SECRET, 8, 6, dev));

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> new JwtService(JwtService.DEV_SECRET, 8, 6, prod));
        assertTrue(boom.getMessage().contains("REFUSING TO START"));
    }
}
