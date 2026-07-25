package com.Exam.Exam_System.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness/readiness probe for the container healthcheck and the load balancer.
 * Public by design — it exposes nothing but a database round-trip result.
 *
 * Deliberately draws from its own small, dedicated connection pool
 * (healthJdbcTemplate, see DataSourceConfig) rather than the application's
 * main one. A real incident: 50 concurrent candidates exhausted the main
 * pool, this endpoint queued behind them for the full 30-second connection
 * timeout, Render's own liveness check gave up waiting on it, and the whole
 * container was restarted — the healthcheck itself became the outage, purely
 * because it shared a resource with the thing it was reporting on.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(@Qualifier("healthJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "db", "UP"));
        } catch (Exception e) {
            // 503 so the load balancer pulls this instance out of rotation until
            // its database connection recovers.
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "db", "DOWN"));
        }
    }
}
