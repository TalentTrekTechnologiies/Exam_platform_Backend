package com.Exam.Exam_System.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One enrolment per candidate per exam, enforced by the database.
 *
 * Every way of enrolling checked "already in this exam?" and then inserted. On
 * a slow college line an admin's Add could be in flight twice at once — the
 * browser gave up on the first, they pressed again — and both requests passed
 * the check before either had written. A roster of 70 became 140.
 *
 * Those duplicates were not just clutter. Start looks up the candidate's one
 * enrolment for the exam, so a candidate enrolled twice could not begin their
 * paper at all, and sign-in offered them the same exam twice to choose from.
 *
 * On every startup this removes any duplicates already written (keeping the
 * first, which is the one any attempt was made against) and then adds the
 * unique key that makes a second one impossible. Both steps are no-ops once
 * done, so it is safe to leave in place.
 */
@Component
@Order(0)
public class EnrolmentIntegrity implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnrolmentIntegrity.class);

    static final String UNIQUE_KEY = "uk_exam_student_student_exam";

    private final JdbcTemplate jdbc;

    public EnrolmentIntegrity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer present = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'exam_student'
                   AND INDEX_NAME = ?
                """, Integer.class, UNIQUE_KEY);
        if (present != null && present > 0) return;

        // Found with one grouped scan, then removed pair by pair. A self-join
        // DELETE says the same thing in one statement, but with no index on the
        // pair it compares every row with every other — on a 16,000-row table it
        // ran for minutes while holding locks the enrolment screens need.
        var duplicated = jdbc.queryForList("""
                SELECT student_id, exam_id, MIN(id) AS keep_id
                  FROM exam_student
                 GROUP BY student_id, exam_id
                HAVING COUNT(*) > 1
                """);

        int removed = 0;
        for (var pair : duplicated) {
            removed += jdbc.update(
                    "DELETE FROM exam_student WHERE student_id = ? AND exam_id = ? AND id > ?",
                    pair.get("student_id"), pair.get("exam_id"), pair.get("keep_id"));
        }
        if (removed > 0) {
            log.warn("Removed {} duplicate enrolment(s): the same candidate entered in the same exam more than once.",
                    removed);
        }

        jdbc.execute("ALTER TABLE exam_student ADD CONSTRAINT " + UNIQUE_KEY
                + " UNIQUE (student_id, exam_id)");
        log.info("exam_student now allows one enrolment per candidate per exam.");
    }
}
