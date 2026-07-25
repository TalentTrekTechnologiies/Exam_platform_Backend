package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.security.AccessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * Results for a finished exam — the reporting counterpart to the live monitor.
 *
 * Monitor answers "what is happening right now"; this answers "how did the
 * cohort do". It is the whole point of a mock exam: a ranked scorecard staff can
 * hand back, and the numbers that tell a candidate whether their raw score was
 * good or poor.
 *
 * Scoped to the owning institution through the same guard as everything else, so
 * one college can never read another's results.
 */
@RestController
@RequestMapping("/admin/report")
public class ReportController {

    private final JdbcTemplate jdbc;
    private final AccessGuard accessGuard;

    public ReportController(JdbcTemplate jdbc, AccessGuard accessGuard) {
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
    }

    private static final String ROSTER = """
            SELECT s.hall_ticket,
                   s.name,
                   a.status       AS attempt_status,
                   a.score,
                   a.submitted_at
              FROM exam_student es
              JOIN students s ON s.id = es.student_id
              JOIN exams    e ON e.id = es.exam_id
              LEFT JOIN attempts a
                     ON a.student_id = es.student_id AND a.exam_id = es.exam_id
             WHERE es.exam_id = ? AND e.admin_id = ?
             ORDER BY s.hall_ticket
            """;

    @GetMapping("/{examId}")
    public Map<String, Object> report(@PathVariable Long examId) {
        Exam exam = accessGuard.requireOwnedExam(examId);

        List<Map<String, Object>> rows = jdbc.query(ROSTER, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hallTicket", rs.getString("hall_ticket"));
            row.put("name", rs.getString("name"));
            String status = rs.getString("attempt_status");
            Object score = rs.getObject("score");
            boolean submitted = "SUBMITTED".equals(status);
            row.put("submitted", submitted);
            // Only a submitted attempt has a meaningful score. An in-progress or
            // never-started candidate has none — shown as such, never as a zero,
            // because a real zero and "didn't sit it" mean very different things.
            row.put("score", submitted ? score : null);
            Timestamp submittedAt = rs.getTimestamp("submitted_at");
            row.put("submittedAt", submittedAt == null ? null : submittedAt.toLocalDateTime());
            return row;
        }, examId, exam.getAdminId());

        // Rank only the submitted candidates. Competition ranking, matching the
        // student-facing result page: equal scores share a rank and the next
        // rank skips, so two candidates tied for top are both 1st and the next
        // is 3rd. Computed here in one pass rather than a subquery per row.
        List<Map<String, Object>> submitted = new ArrayList<>();
        for (Map<String, Object> r : rows) if (Boolean.TRUE.equals(r.get("submitted"))) submitted.add(r);
        submitted.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));

        double total = 0, top = submitted.isEmpty() ? 0 : scoreOf(submitted.get(0));
        for (int idx = 0; idx < submitted.size(); idx++) {
            Map<String, Object> r = submitted.get(idx);
            double sc = scoreOf(r);
            total += sc;
            // Share a rank with the previous candidate on an equal score.
            if (idx > 0 && sc == scoreOf(submitted.get(idx - 1))) {
                r.put("rank", submitted.get(idx - 1).get("rank"));
            } else {
                r.put("rank", idx + 1);
            }
        }

        int submittedCount = submitted.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examId", examId);
        out.put("examTitle", exam.getTitle());
        out.put("totalCandidates", rows.size());
        out.put("submittedCount", submittedCount);
        out.put("notSubmittedCount", rows.size() - submittedCount);
        out.put("averageScore", submittedCount == 0 ? null : Math.round(total / submittedCount * 10) / 10.0);
        out.put("topScore", submittedCount == 0 ? null : top);
        // Candidates ranked first, then everyone who has not submitted, so the
        // meaningful rows are at the top of the table.
        List<Map<String, Object>> ordered = new ArrayList<>(submitted);
        for (Map<String, Object> r : rows) if (!Boolean.TRUE.equals(r.get("submitted"))) ordered.add(r);
        out.put("candidates", ordered);
        return out;
    }

    private static double scoreOf(Map<String, Object> row) {
        Object s = row.get("score");
        return s instanceof Number n ? n.doubleValue() : 0.0;
    }
}
