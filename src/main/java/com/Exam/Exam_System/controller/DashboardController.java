package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.repository.ExamViolationRepository;
import com.Exam.Exam_System.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Analytics for the admin dashboard.
 *
 * The dashboard has always called these three endpoints; they simply never
 * existed, so every load logged failures and the page rendered zeros. Every
 * query is scoped through `exams.admin_id`, so one institution's dashboard can
 * never total another's candidates.
 */
@RestController
@RequestMapping("/admin")
public class DashboardController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ExamViolationRepository violationRepository;

    public DashboardController(JdbcTemplate jdbc,
                               CurrentUser currentUser,
                               ExamViolationRepository violationRepository) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.violationRepository = violationRepository;
    }

    /**
     * Mean score as a percentage. Scores are stored as raw marks, so each attempt
     * is divided by the total marks available on its own paper before averaging —
     * otherwise a 4-mark paper and a 720-mark paper would be averaged together.
     */
    @GetMapping("/exams/average-score")
    public Map<String, Object> averageScore() {
        Double avg = jdbc.queryForObject("""
                SELECT COALESCE(ROUND(AVG(t.pct), 1), 0)
                  FROM (
                        SELECT a.score / NULLIF(q.total, 0) * 100 AS pct
                          FROM attempts a
                          JOIN exams e ON e.id = a.exam_id
                          JOIN (SELECT exam_id, SUM(marks) AS total
                                  FROM questions GROUP BY exam_id) q
                            ON q.exam_id = a.exam_id
                         WHERE e.admin_id = ? AND a.status = 'SUBMITTED'
                       ) t
                """, Double.class, currentUser.adminId());

        return Map.of("average", avg == null ? 0.0 : avg);
    }

    /** The latest starts and submissions across this institution's exams. */
    @GetMapping("/activities/recent")
    public List<Map<String, Object>> recentActivity() {
        return jdbc.query("""
                SELECT a.id,
                       s.name AS student_name,
                       a.status,
                       a.score,
                       COALESCE(a.submitted_at, a.start_time) AS happened_at,
                       q.total AS paper_total
                  FROM attempts a
                  JOIN exams e    ON e.id = a.exam_id
                  JOIN students s ON s.id = a.student_id
                  LEFT JOIN (SELECT exam_id, SUM(marks) AS total
                               FROM questions GROUP BY exam_id) q
                    ON q.exam_id = a.exam_id
                 WHERE e.admin_id = ?
                   AND a.status <> 'PENDING'
                 ORDER BY happened_at DESC
                 LIMIT 8
                """, (rs, i) -> {
            boolean submitted = "SUBMITTED".equals(rs.getString("status"));
            var at = rs.getTimestamp("happened_at");
            double total = rs.getDouble("paper_total");
            Double score = (Double) rs.getObject("score");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("studentName", rs.getString("student_name"));
            row.put("action", submitted ? "submitted the exam" : "started the exam");
            row.put("time", at == null ? "" : at.toLocalDateTime().format(WHEN));
            // Only a finished attempt has a meaningful score to show.
            row.put("score", submitted && score != null && total > 0
                    ? Math.round(score / total * 1000) / 10.0
                    : null);
            return row;
        }, currentUser.adminId());
    }

    /**
     * The integrity report: which candidates triggered proctoring events and how
     * many. This is the record an invigilator needs when a result is challenged.
     */
    @GetMapping("/violations")
    public List<Map<String, Object>> violations(@RequestParam(required = false) Long examId) {
        return violationRepository.summariseForAdmin(currentUser.adminId(), examId).stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("hallTicket", r[0]);
                    row.put("studentName", r[1]);
                    row.put("attemptId", r[2]);
                    row.put("violations", r[3]);
                    row.put("lastAt", r[4]);
                    return row;
                })
                .toList();
    }

    /** Six months of starts and completions, for the dashboard chart. */
    @GetMapping("/charts/monthly")
    public Map<String, Object> monthly() {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT DATE_FORMAT(a.start_time, '%Y-%m') AS ym,
                       DATE_FORMAT(a.start_time, '%b')    AS label,
                       COUNT(*)                            AS started,
                       SUM(a.status = 'SUBMITTED')         AS completed
                  FROM attempts a
                  JOIN exams e ON e.id = a.exam_id
                 WHERE e.admin_id = ?
                   AND a.start_time IS NOT NULL
                   AND a.start_time >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                 GROUP BY ym, label
                 ORDER BY ym
                """, (rs, i) -> Map.of(
                        "label", rs.getString("label"),
                        "started", rs.getInt("started"),
                        "completed", rs.getInt("completed")),
                currentUser.adminId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", rows.stream().map(r -> r.get("label")).toList());
        out.put("registrations", rows.stream().map(r -> r.get("started")).toList());
        out.put("completions", rows.stream().map(r -> r.get("completed")).toList());
        return out;
    }
}
