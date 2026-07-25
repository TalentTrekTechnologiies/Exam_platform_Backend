package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.dto.ResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a candidate stands against everyone else who sat the same paper.
 *
 * This is the point of a mock exam. A raw score tells a student almost nothing —
 * 96/180 could be excellent or poor, and they cannot know which. Rank, percentile
 * and the cohort's section averages are what turn a scorecard into something
 * worth revising from.
 *
 * Only SUBMITTED attempts are ranked. Ranking against people still writing would
 * flatter early finishers and mislead everyone.
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    /**
     * Below this, a rank is noise rather than information — being "2nd of 3"
     * says nothing useful, and publishing it invites a student to read meaning
     * into a number that has none.
     */
    private static final int MIN_COHORT_FOR_RANK = 5;

    private final JdbcTemplate jdbc;

    public RankingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fills in rank, percentile and cohort comparisons.
     *
     * Deliberately leaves them null when the cohort is too small to mean
     * anything, so the UI can stay quiet rather than print something misleading.
     */
    @Transactional(readOnly = true)
    public void enrich(ResultResponse result, Attempt attempt) {
        Long examId = attempt.getExamId();
        double score = attempt.getScore() == null ? 0 : attempt.getScore();

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_id = ? AND status = 'SUBMITTED'",
                Integer.class, examId);
        int ranked = total == null ? 0 : total;
        result.setTotalRanked(ranked);

        if (ranked < MIN_COHORT_FOR_RANK) {
            log.debug("Exam {} has only {} submitted attempt(s) — withholding rank.", examId, ranked);
            return;
        }

        // Competition ranking: everyone on the same score shares a rank, and the
        // rank after a tie skips. Two candidates on the top score are both 1st,
        // and the next is 3rd — which is what a candidate expects to see.
        Integer above = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_id = ? AND status = 'SUBMITTED' AND score > ?",
                Integer.class, examId, score);
        result.setRank((above == null ? 0 : above) + 1);

        // Percentile: the share scoring strictly below. Stated this way because
        // "percentile" is used loosely elsewhere and the definition matters when
        // a student compares two platforms.
        Integer below = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_id = ? AND status = 'SUBMITTED' AND score < ?",
                Integer.class, examId, score);
        result.setPercentile(Math.round((below == null ? 0 : below) * 1000.0 / ranked) / 10.0);

        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT MAX(score) AS top, AVG(score) AS avg FROM attempts "
                        + "WHERE exam_id = ? AND status = 'SUBMITTED'", examId);
        result.setTopScore(toDouble(stats.get("top")));
        Double avg = toDouble(stats.get("avg"));
        result.setCohortAverage(avg == null ? null : Math.round(avg * 10) / 10.0);

        applySectionAverages(result, examId);
    }

    /**
     * The cohort's average score in each section.
     *
     * Computed per candidate per section, then averaged — not a raw average over
     * answers, which would weight a candidate who attempted more questions more
     * heavily and quietly distort the comparison.
     *
     * This is the heaviest query in the result path. It runs when a candidate
     * opens their scorecard, never during the exam itself, so it competes with
     * nothing time-critical. If a very large cohort makes it slow, the natural
     * fix is to compute it once per exam and cache it rather than to drop it.
     */
    private void applySectionAverages(ResultResponse result, Long examId) {
        if (result.getSections() == null || result.getSections().isEmpty()) return;

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT section_id, AVG(section_score) AS avg_score
                  FROM (
                        SELECT a.id AS attempt_id,
                               q.section_id AS section_id,
                               SUM(CASE
                                     WHEN ans.is_correct = 1 THEN q.marks
                                     WHEN ans.selected_option IS NOT NULL THEN -COALESCE(q.negative_marks, 0)
                                     ELSE 0
                                   END) AS section_score
                          FROM attempts a
                          JOIN attempt_questions aq ON aq.attempt_id = a.id
                          JOIN questions q          ON q.id = aq.question_id
                          LEFT JOIN answers ans     ON ans.attempt_id = a.id AND ans.question_id = q.id
                         WHERE a.exam_id = ? AND a.status = 'SUBMITTED'
                         GROUP BY a.id, q.section_id
                       ) per_candidate
                 GROUP BY section_id
                """, examId);

        Map<Long, Double> averages = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object sectionId = row.get("section_id");
            Double avg = toDouble(row.get("avg_score"));
            if (sectionId != null && avg != null) {
                averages.put(((Number) sectionId).longValue(), Math.round(avg * 10) / 10.0);
            }
        }

        for (ResultResponse.SectionScore section : result.getSections()) {
            if (section.getSectionId() != null) {
                section.setCohortAverage(averages.get(section.getSectionId()));
            }
        }
    }

    private Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
