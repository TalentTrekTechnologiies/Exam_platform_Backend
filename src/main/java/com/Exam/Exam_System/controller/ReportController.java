package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final CurrentUser currentUser;

    public ReportController(JdbcTemplate jdbc, AccessGuard accessGuard,
                            CurrentUser currentUser) {
        this.currentUser = currentUser;
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


    /**
     * Every exam this college has run, with its headline result.
     *
     * The detailed report answers "how did this cohort do"; this answers the
     * question that comes before it — which exam, and how did they compare.
     * Without it the results screen could only ever show whichever exam
     * happened to be open, and a past paper was unreachable without going back
     * to build it again.
     *
     * One aggregate query rather than a report each: a college with thirty
     * exams should not cost thirty round trips to draw a list.
     */
    @GetMapping
    public List<Map<String, Object>> allExamReports() {
        String sql = """
                SELECT e.id,
                       e.title,
                       e.start_date,
                       e.published,
                       e.results_released,
                       COUNT(DISTINCT es.student_id) AS candidates,
                       COUNT(DISTINCT CASE WHEN a.status = 'SUBMITTED' THEN a.student_id END) AS submitted,
                       AVG(CASE WHEN a.status = 'SUBMITTED' THEN a.score END) AS avg_score,
                       MAX(CASE WHEN a.status = 'SUBMITTED' THEN a.score END) AS top_score
                  FROM exams e
                  LEFT JOIN exam_student es ON es.exam_id = e.id
                  LEFT JOIN attempts a      ON a.exam_id = e.id AND a.student_id = es.student_id
                 WHERE e.admin_id = ?
                 GROUP BY e.id, e.title, e.start_date, e.published, e.results_released
                 ORDER BY e.id DESC
                """;

        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("examId", rs.getLong("id"));
            row.put("examTitle", rs.getString("title"));
            java.sql.Timestamp started = rs.getTimestamp("start_date");
            row.put("startDate", started == null ? null : started.toLocalDateTime());
            row.put("published", rs.getBoolean("published"));
            row.put("resultsReleased", rs.getBoolean("results_released"));
            row.put("totalCandidates", rs.getInt("candidates"));
            row.put("submittedCount", rs.getInt("submitted"));

            double avg = rs.getDouble("avg_score");
            row.put("averageScore", rs.wasNull() ? null : Math.round(avg * 10) / 10.0);
            double top = rs.getDouble("top_score");
            row.put("topScore", rs.wasNull() ? null : top);
            return row;
        }, currentUser.adminId());
    }

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
        out.put("resultsReleased", exam.isResultsReleased());
        out.put("resultsReleasedAt", exam.getResultsReleasedAt());
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
