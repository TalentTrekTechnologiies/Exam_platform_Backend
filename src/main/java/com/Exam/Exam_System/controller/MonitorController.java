package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.service.ProctorFrameService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Live view of a sitting in progress.
 *
 * Without this an invigilator is blind across a hall: no way to see who never
 * started, whose machine dropped, who is close to running out of time, or who
 * is racking up proctoring violations — until it is too late to intervene.
 *
 * Everything is one query per refresh, scoped to the owning institution.
 */
@RestController
@RequestMapping("/admin/monitor")
public class MonitorController {

    private final ProctorFrameService proctorFrameService;

    /**
     * A candidate is treated as having dropped when their attempt is live but
     * nothing has reached the server recently. Answers and clock polls both
     * touch the server, so silence this long means the machine, the browser or
     * the network is gone — not that they are thinking.
     */
    private static final long STALE_AFTER_SECONDS = 120;

    private final JdbcTemplate jdbc;
    private final AccessGuard accessGuard;

    public MonitorController(JdbcTemplate jdbc, AccessGuard accessGuard,
                             ProctorFrameService proctorFrameService) {
        this.proctorFrameService = proctorFrameService;
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
    }

    private static final String ROSTER = """
            SELECT s.hall_ticket,
                   s.name,
                   a.id            AS attempt_id,
                   a.status        AS attempt_status,
                   a.start_time,
                   a.end_time,
                   a.submitted_at,
                   a.score,
                   COALESCE(ans.answered, 0)    AS answered,
                   COALESCE(vio.violations, 0)  AS violations,
                   ans.last_activity
              FROM exam_student es
              JOIN students s ON s.id = es.student_id
              JOIN exams    e ON e.id = es.exam_id
              LEFT JOIN attempts a
                     ON a.student_id = es.student_id AND a.exam_id = es.exam_id
              LEFT JOIN (SELECT attempt_id,
                                COUNT(*)        AS answered,
                                MAX(updated_at) AS last_activity
                           FROM answers
                          WHERE selected_option IS NOT NULL
                          GROUP BY attempt_id) ans ON ans.attempt_id = a.id
              LEFT JOIN (SELECT attempt_id, COUNT(*) AS violations
                           FROM exam_violations
                          GROUP BY attempt_id) vio ON vio.attempt_id = a.id
             WHERE es.exam_id = ? AND e.admin_id = ?
             ORDER BY s.hall_ticket
            """;

    /**
     * Every candidate on this exam and where they currently stand.
     *
     * Statuses the invigilator acts on:
     *   NOT_STARTED — enrolled but has not begun
     *   IN_PROGRESS — writing, with time left and answers landing
     *   DISCONNECTED — started, but nothing has reached us in two minutes
     *   SUBMITTED   — finished
     */
    @GetMapping("/{examId}")
    public Map<String, Object> monitor(@PathVariable Long examId) {
        var exam = accessGuard.requireOwnedExam(examId);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> candidates = jdbc.query(ROSTER, (rs, i) -> {
            String attemptStatus = rs.getString("attempt_status");
            Timestamp lastActivity = rs.getTimestamp("last_activity");
            Timestamp startTime = rs.getTimestamp("start_time");
            Timestamp endTime = rs.getTimestamp("end_time");

            String state;
            Long remaining = null;

            if (attemptStatus == null || "PENDING".equals(attemptStatus)) {
                state = "NOT_STARTED";
            } else if ("SUBMITTED".equals(attemptStatus)) {
                state = "SUBMITTED";
            } else {
                remaining = endTime == null ? null
                        : Math.max(0, Duration.between(now, endTime.toLocalDateTime()).getSeconds());

                // Fall back to start time: a candidate who has begun but not yet
                // answered anything still counts as recently seen.
                LocalDateTime seen = lastActivity != null ? lastActivity.toLocalDateTime()
                        : (startTime != null ? startTime.toLocalDateTime() : null);
                boolean stale = seen != null
                        && Duration.between(seen, now).getSeconds() > STALE_AFTER_SECONDS;

                state = stale ? "DISCONNECTED" : "IN_PROGRESS";
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hallTicket", rs.getString("hall_ticket"));
            row.put("name", rs.getString("name"));
            row.put("attemptId", rs.getObject("attempt_id"));
            row.put("state", state);
            row.put("answered", rs.getInt("answered"));
            row.put("violations", rs.getInt("violations"));
            row.put("remainingSeconds", remaining);
            row.put("startedAt", startTime == null ? null : startTime.toLocalDateTime());
            row.put("submittedAt", rs.getObject("submitted_at") == null
                    ? null : rs.getTimestamp("submitted_at").toLocalDateTime());
            row.put("lastSeen", lastActivity == null ? null : lastActivity.toLocalDateTime());
            row.put("score", rs.getObject("score"));
            return row;
        }, examId, exam.getAdminId());

        // Headline counts, so the invigilator sees the shape of the hall at a glance.
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String s : List.of("NOT_STARTED", "IN_PROGRESS", "DISCONNECTED", "SUBMITTED")) tally.put(s, 0);
        int flagged = 0;
        for (Map<String, Object> c : candidates) {
            tally.merge((String) c.get("state"), 1, Integer::sum);
            if (((Integer) c.get("violations")) > 0) flagged++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examId", examId);
        out.put("examTitle", exam.getTitle());
        out.put("total", candidates.size());
        out.put("counts", tally);
        out.put("flagged", flagged);
        out.put("serverTime", now);
        out.put("candidates", candidates);
        return out;
    }

    /**
     * One candidate's newest camera frame.
     *
     * Guarded through the exam, not the attempt id alone: an invigilator may
     * only see candidates sitting an exam their own institution owns.
     *
     * Marked no-store because these are a live view, not a record. A cached
     * copy in a proxy would both show a stale seat and leave a candidate's face
     * somewhere nobody intended it to be.
     */
    @GetMapping(value = "/{examId}/frame/{attemptId}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> candidateFrame(@PathVariable Long examId,
                                                 @PathVariable Long attemptId) {
        accessGuard.requireOwnedExam(examId);
        if (!attemptBelongsToExam(attemptId, examId)) {
            return ResponseEntity.notFound().build();
        }
        byte[] frame = proctorFrameService.read(attemptId);
        if (frame == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .contentType(MediaType.IMAGE_JPEG)
                .body(frame);
    }

    /** Stops an attempt id from one exam being used to view another's camera. */
    private boolean attemptBelongsToExam(Long attemptId, Long examId) {
        Long found = jdbc.query(
                "SELECT exam_id FROM attempts WHERE id = ?",
                rs -> rs.next() ? rs.getLong("exam_id") : null,
                attemptId);
        return examId.equals(found);
    }

}
