package com.Exam.Exam_System.security;

// Spring Boot 4 / this project's spring-boot-starter-web ships Jackson 3, whose
// classes live under tools.jackson.* — the classic com.fasterxml.jackson.databind
// package is present transitively only as a runtime-scoped dependency of jjwt,
// invisible at compile time to this class.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down guessing against the two unauthenticated front doors — but the
 * two doors need different keys, not the same one.
 *
 * /admin/login is naturally low-volume and IP-based limiting fits: there are
 * few institutions, from few locations, and repeated failures from one IP is a
 * real signal.
 *
 * /student/validate is not. A real exam hall of hundreds of candidates can sit
 * behind ONE shared NAT address — an IP-keyed limit tried here first and did
 * exactly what it should never do: it would have locked out the eleventh
 * legitimate student in a lab, at the worst possible moment, on exam morning.
 * The actual threat on this endpoint is guessing *one* candidate's name
 * against their hall ticket, so the limit is keyed on the hall ticket being
 * attempted instead — that stops a targeted guess without ever penalising
 * concurrent, unrelated students sharing a gateway.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final int LOGIN_MAX_ATTEMPTS = 10;
    private static final int VALIDATE_MAX_ATTEMPTS = 10;
    private static final long WINDOW_MILLIS = 5 * 60 * 1000;

    /** Bounded the same way AttemptCache is — many distinct keys must not leak memory forever. */
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Window(AtomicInteger count, long windowStart) {}

    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        boolean isLogin = "POST".equals(request.getMethod()) && "/admin/login".equals(request.getRequestURI());
        boolean isValidate = "POST".equals(request.getMethod()) && "/student/validate".equals(request.getRequestURI());

        if (!isLogin && !isValidate) {
            chain.doFilter(request, response);
            return;
        }

        // Wrapped so the body can be read here AND still reach the real
        // controller afterwards — a plain request stream is consumable once.
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);

        String key = isLogin
                ? "login|" + clientIp(cached)
                : "validate|" + hallTicketFrom(cached).orElseGet(() -> clientIp(cached));
        int limit = isLogin ? LOGIN_MAX_ATTEMPTS : VALIDATE_MAX_ATTEMPTS;

        long now = System.currentTimeMillis();
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.entrySet().removeIf(e -> now - e.getValue().windowStart() > WINDOW_MILLIS);
        }

        Window window = attempts.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart() > WINDOW_MILLIS) {
                return new Window(new AtomicInteger(1), now);
            }
            existing.count().incrementAndGet();
            return existing;
        });

        if (window.count().get() > limit) {
            long retryAfterSeconds = Math.max(1, (WINDOW_MILLIS - (now - window.windowStart())) / 1000);
            log.warn("Rate limit hit on {} for key {}", request.getRequestURI(), key);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":429,\"code\":\"TOO_MANY_ATTEMPTS\","
                    + "\"message\":\"Too many attempts. Try again in a minute.\"}");
            return;
        }

        chain.doFilter(cached, response);
    }

    private java.util.Optional<String> hallTicketFrom(CachedBodyHttpServletRequest request) {
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String ticket = body.path("hallTicket").asText(null);
            return ticket == null || ticket.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(ticket.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            // Malformed JSON reaches the real controller and gets a proper 400 —
            // this filter's only job is deciding whether to rate-limit, not to
            // validate the request shape.
            return java.util.Optional.empty();
        }
    }

    /**
     * Render (and any reverse proxy) terminates the real connection and sets this
     * header itself — a client cannot reach this server without going through
     * it. Take the first entry, the conventional "original client" position.
     * Falls back to the direct connection for local/VPS deployment, where there
     * is no proxy in front at all.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
