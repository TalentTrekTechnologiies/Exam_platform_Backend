package com.Exam.Exam_System.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds papers for candidates who have none, without anyone pressing a button.
 *
 * Papers are prepared in advance so that a hall pressing Start together is a
 * status change each, not hundreds of papers built in the same second. That
 * used to be a "Prepare papers" button staff had to remember after importing
 * questions, and again after every late enrolment; publishing now prepares
 * them, and this catches everyone added afterwards -- and every exam that was
 * published before publishing did.
 *
 * Only ever creates missing papers, never rebuilds one: it runs while exams
 * are open, and a paper a candidate is about to start must not change under
 * them. Rebuilding to match changed settings stays the button's job.
 */
@Component
public class PaperPreparer {

    private static final Logger log = LoggerFactory.getLogger(PaperPreparer.class);

    private final JdbcTemplate jdbc;
    private final AttemptService attemptService;

    public PaperPreparer(JdbcTemplate jdbc, AttemptService attemptService) {
        this.jdbc = jdbc;
        this.attemptService = attemptService;
    }

    /** Published exams with a sitting still to finish, and someone in them without a paper. */
    @Scheduled(initialDelayString = "${exam.prepare.initial-delay-ms:60000}",
               fixedDelayString = "${exam.prepare.interval-ms:120000}")
    public void prepareWaitingCandidates() {
        List<Long> exams;
        try {
            exams = jdbc.queryForList("""
                    SELECT DISTINCT es.exam_id
                      FROM exam_student es
                      JOIN exams e ON e.id = es.exam_id
                      LEFT JOIN attempts a ON a.student_id = es.student_id AND a.exam_id = es.exam_id
                     WHERE e.published = 1
                       AND a.id IS NULL
                       AND EXISTS (SELECT 1 FROM exam_slots s
                                    WHERE s.exam_id = e.id AND s.end_time > NOW())
                    """, Long.class);
        } catch (RuntimeException e) {
            log.warn("Could not look for candidates without a paper: {}", e.toString());
            return;
        }

        for (Long examId : exams) {
            try {
                attemptService.prepareMissing(examId);
            } catch (RuntimeException e) {
                // One exam's trouble is not every exam's: carry on with the rest.
                log.warn("Preparing papers for exam {} failed: {}", examId, e.toString());
            }
        }
    }
}
