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
        repairRemovedSittings();
        ensureOneEnrolmentPerExam();
    }

    /**
     * Enrolments naming a sitting that has since been deleted.
     *
     * Deleting a slot with candidates in it used to be allowed, and left each
     * of them unable to sign in at all. Where the exam has exactly one sitting
     * left, that is the only place they can have meant, so they are moved
     * there. Where it has several, choosing for them could put a candidate in
     * the wrong batch, so they are left for the college to place and sign-in
     * tells them to ask the invigilator. Runs every start; nothing to do after.
     */
    private void repairRemovedSittings() {
        int moved = jdbc.update("""
                UPDATE exam_student es
                  JOIN (SELECT exam_id, MIN(id) AS only_slot
                          FROM exam_slots
                         GROUP BY exam_id
                        HAVING COUNT(*) = 1) one ON one.exam_id = es.exam_id
                  LEFT JOIN exam_slots gone ON gone.id = es.slot_id
                   SET es.slot_id = one.only_slot
                 WHERE es.slot_id IS NOT NULL
                   AND gone.id IS NULL
                """);
        if (moved > 0) {
            log.warn("Moved {} candidate(s) whose sitting had been deleted into their exam's only remaining sitting.",
                    moved);
        }

        var stranded = jdbc.queryForList("""
                SELECT es.exam_id, COUNT(*) AS candidates
                  FROM exam_student es
                  LEFT JOIN exam_slots s ON s.id = es.slot_id
                 WHERE es.slot_id IS NOT NULL AND s.id IS NULL
                 GROUP BY es.exam_id
                """);
        for (var row : stranded) {
            log.warn("Exam {}: {} candidate(s) are in a sitting that was deleted and cannot sign in. "
                            + "Move them to a sitting from the roster on Add Candidates.",
                    row.get("exam_id"), row.get("candidates"));
        }
    }

    private void ensureOneEnrolmentPerExam() {
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
