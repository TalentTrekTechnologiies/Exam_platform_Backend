package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.ExamStudent;
import com.Exam.Exam_System.dto.ResultResponse;
import com.Exam.Exam_System.repository.AttemptRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

    /** Paper frozen and waiting; the candidate has not begun and no clock runs. */
    public static final String PENDING = "PENDING";
    /** Live: the clock is running and answers are accepted. */
    public static final String STARTED = "STARTED";

    private final AttemptRepository attemptRepository;
    private final ExamRepository examRepository;
    private final SlotRepository slotRepository;
    private final PaperService paperService;
    private final ScoringService scoringService;
    private final AttemptCache attemptCache;
    private final ExamStudentRepository examStudentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SectionClockService sectionClock;

    /**
     * How long a silence has to last before it counts as a disconnection.
     *
     * The paper polls every twenty seconds, so a silence of up to about two
     * polls is ordinary jitter -- a slow response, a garbage collection.
     * Returning time for those would hand out seconds nobody lost. Anything
     * longer is a real drop, and all of it is given back.
     */
    @Value("${exam.reconnect.grace-seconds:60}")
    private int reconnectGraceSeconds;

    /**
     * The most time any one candidate can be given back, across the sitting.
     *
     * There has to be a ceiling, and this is the whole reason: a candidate who
     * works out that disconnecting stops their clock has been handed unlimited
     * time with a textbook. The cap turns that from an exploit into a
     * nuisance, and every second of it is recorded against the attempt for an
     * invigilator to look at.
     */
    @Value("${exam.reconnect.max-seconds:900}")
    private int reconnectMaxSeconds;

    @PersistenceContext
    private EntityManager entityManager;

    public AttemptService(AttemptRepository attemptRepository,
                          ExamRepository examRepository,
                          SlotRepository slotRepository,
                          PaperService paperService,
                          ScoringService scoringService,
                          AttemptCache attemptCache,
                          ExamStudentRepository examStudentRepository,
                          JdbcTemplate jdbcTemplate,
                          SectionClockService sectionClock) {
        this.attemptRepository = attemptRepository;
        this.examRepository = examRepository;
        this.slotRepository = slotRepository;
        this.paperService = paperService;
        this.scoringService = scoringService;
        this.attemptCache = attemptCache;
        this.examStudentRepository = examStudentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.sectionClock = sectionClock;
    }

    /**
     * Starts (or resumes) an attempt. Safe to call repeatedly: a candidate who
     * refreshes, reconnects, or double-taps Start always lands back on the same
     * attempt with the same paper and the same original clock.
     */
    @Transactional
    public Attempt startOrResume(Long studentId, Long examId, Long slotId) {
        Attempt existing = attemptRepository.findByStudentIdAndExamId(studentId, examId).orElse(null);

        if (existing != null) {
            // Pre-built by the admin ahead of the exam: activating it is a single
            // row update, with the paper already frozen. This is what turns the
            // slot-open stampede from thousands of inserts into thousands of
            // cheap updates.
            if (PENDING.equals(existing.getStatus())) {
                return activate(existing, examId, slotId);
            }
            paperService.buildPaper(existing.getId(), examId); // no-op if already frozen
            return existing;
        }

        // No pre-built attempt — create one on the spot. Keeps the platform
        // working for admins who never ran the prepare step.
        int durationMinutes = examRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("Exam not found"))
                .getDuration();

        // The full duration from the moment they start, whenever that is. A
        // candidate who arrives at 5:10 for a 5:00 sitting of one hour writes
        // until 6:10. The slot's end is the last moment to START, checked at
        // sign-in; it no longer cuts short the people who began in time.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusMinutes(durationMinutes);

        Attempt attempt = new Attempt();
        attempt.setStudentId(studentId);
        attempt.setExamId(examId);
        attempt.setSlotId(slotId);
        attempt.setStartTime(now);
        attempt.setEndTime(endTime);
        attempt.setStatus("STARTED");

        try {
            attempt = attemptRepository.save(attempt);
        } catch (DataIntegrityViolationException race) {
            // Two concurrent Start calls; the unique constraint kept us honest.
            attempt = attemptRepository.findByStudentIdAndExamId(studentId, examId)
                    .orElseThrow(() -> race);
            paperService.buildPaper(attempt.getId(), examId);
            return attempt;
        }

        paperService.buildPaper(attempt.getId(), examId);
        sectionClock.beginFirstSection(attempt);
        return attempt;
    }

    /**
     * Starts the clock on an attempt whose paper is already frozen.
     *
     * One statement does the whole job: it reads the exam's duration, sets the
     * deadline that far from now, and flips the status — atomically, and only
     * if the attempt is still PENDING, so two simultaneous clicks cannot double
     * start it. This is the hot path at slot-open, when thousands of candidates
     * hit Start in the same second, so it is deliberately one round trip rather
     * than the four lookups it replaces.
     */
    private static final String ACTIVATE = """
            UPDATE attempts a
              JOIN exams e ON e.id = a.exam_id
               SET a.status     = 'STARTED',
                   a.start_time = NOW(),
                   a.end_time   = DATE_ADD(NOW(), INTERVAL e.duration MINUTE)
             WHERE a.id = ? AND a.status = 'PENDING'
            """;

    private Attempt activate(Attempt attempt, Long examId, Long slotId) {
        jdbcTemplate.update(ACTIVATE, attempt.getId());

        // The native update bypassed the persistence context, so the managed
        // entity still holds the pre-activation values — pull the new ones back.
        entityManager.refresh(attempt);

        // Cheap insurance in case preparation was interrupted before this
        // candidate's paper was written; a no-op in the normal case.
        paperService.buildPaper(attempt.getId(), examId);

        // The first section opens as the paper's own clock does. On an exam
        // that is not sectional this does nothing at all.
        sectionClock.beginFirstSection(attempt);
        return attempt;
    }

    /**
     * Builds attempts and freezes papers for every candidate assigned to an exam,
     * ahead of the sitting. Run this the night before.
     *
     * The work at slot-open is otherwise enormous: at national scale, 100,000
     * candidates each freezing a 180-row paper is 18 million inserts crammed into
     * the seconds after the clock opens. Doing it in advance leaves Start as a
     * single row update. Idempotent, so it can be re-run safely as late
     * registrations come in.
     */
    @Transactional
    /**
     * Papers for the candidates of an exam who have none yet, and nothing else.
     *
     * The automatic counterpart to prepareAttempts: it runs on publishing and
     * every couple of minutes while an exam has a sitting to come, including
     * while candidates are starting. So it never rebuilds an existing paper,
     * and it is deliberately not one transaction: each paper is created and
     * committed on its own, and a candidate who pressed Start in the same
     * moment (the unique key on student and exam refuses the second paper) is
     * skipped rather than undoing every paper built before them.
     */
    public int prepareMissing(Long examId) {
        List<Map<String, Object>> missing = jdbcTemplate.queryForList("""
                SELECT es.student_id, es.slot_id
                  FROM exam_student es
                  LEFT JOIN attempts a ON a.student_id = es.student_id AND a.exam_id = es.exam_id
                 WHERE es.exam_id = ? AND a.id IS NULL
                """, examId);

        int made = 0;
        for (Map<String, Object> row : missing) {
            Attempt attempt = new Attempt();
            attempt.setStudentId(((Number) row.get("student_id")).longValue());
            attempt.setExamId(examId);
            Object slot = row.get("slot_id");
            attempt.setSlotId(slot == null ? null : ((Number) slot).longValue());
            attempt.setStatus(PENDING);
            try {
                Attempt saved = attemptRepository.save(attempt);
                paperService.buildPaper(saved.getId(), examId);
                made++;
            } catch (DataIntegrityViolationException alreadyStarted) {
                // They pressed Start while this ran; Start built their paper.
            }
        }
        if (made > 0) {
            log.info("Prepared {} missing paper(s) for exam {}.", made, examId);
        }
        return made;
    }

    public Map<String, Object> prepareAttempts(Long examId) {
        List<ExamStudent> mappings = examStudentRepository.findByExamId(examId);

        int prepared = 0;
        int alreadyReady = 0;
        int rebuilt = 0;

        for (ExamStudent mapping : mappings) {
            Attempt existing = attemptRepository
                    .findByStudentIdAndExamId(mapping.getStudentId(), examId).orElse(null);
            if (existing != null) {
                // A paper somebody is sitting, or has sat, is left exactly as it
                // is: their answers are keyed to the questions they were given.
                if (!PENDING.equals(existing.getStatus())) {
                    alreadyReady++;
                    continue;
                }

                // An unstarted paper is brought in line with the exam as it is
                // configured now — which is what makes changing a section's
                // draw count after preparing actually take effect.
                if (paperService.ensurePaperMatchesSettings(existing.getId(), examId)) rebuilt++;
                else alreadyReady++;
                continue;
            }

            Attempt attempt = new Attempt();
            attempt.setStudentId(mapping.getStudentId());
            attempt.setExamId(examId);
            attempt.setSlotId(mapping.getSlotId());
            attempt.setStatus(PENDING);
            // No times yet — the clock starts only when the candidate does.
            Attempt saved = attemptRepository.save(attempt);

            paperService.buildPaper(saved.getId(), examId);
            prepared++;
        }

        log.info("Prepared {} papers for exam {} ({} rebuilt, {} already ready).",
                prepared, examId, rebuilt, alreadyReady);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("examId", examId);
        report.put("candidates", mappings.size());
        report.put("prepared", prepared);
        report.put("rebuilt", rebuilt);
        report.put("alreadyReady", alreadyReady);

        List<String> parts = new ArrayList<>();
        if (prepared > 0) parts.add("Prepared " + prepared + " paper(s)");
        if (rebuilt > 0) parts.add("rebuilt " + rebuilt + " to match the current settings");
        if (alreadyReady > 0) parts.add(alreadyReady + " already ready");
        report.put("summary", parts.isEmpty()
                ? "There are no candidates on this exam yet."
                : String.join("; ", parts) + ".");
        return report;
    }

    /** One attempt, by id. Used by the clock poll to read its section state. */
    @Transactional(readOnly = true)
    public Attempt getById(Long attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));
    }

    @Transactional(readOnly = true)
    public Attempt getAttempt(Long studentId, Long examId) {
        return attemptRepository.findByStudentIdAndExamId(studentId, examId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Attempt requireAttempt(Long attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));
    }

    /**
     * Seconds left on the server's clock — the only clock that counts. The
     * frontend used to read this from localStorage, where a candidate could
     * simply type themselves more time.
     *
     * This is the single most-polled endpoint (every candidate, every ~20s). The
     * deadline never changes once an attempt starts, so the common case is
     * answered entirely from memory — no query at all. Deliberately NOT
     * annotated @Transactional: only the rare expiry grades, and it does so
     * through the grader's own write transaction. Wrapping the poll in a
     * read-only transaction would force that write to join a read-only context
     * and fail to flush.
     */
    /**
     * Returns the time a disconnection cost, and stamps the heartbeat with it.
     *
     * Runs when a candidate's browser speaks again after a silence longer than
     * the grace period. The gap is added to their deadline, capped at whatever
     * is left of their personal allowance. Not at the slot's close: that is the
     * last moment to start, and a candidate who started in time and then lost
     * their connection is owed the time they lost whatever the clock says.
     *
     * Deliberately one statement. The read-then-write version of this has a
     * race that two browser tabs, or a reconnect landing beside a retry, would
     * find at the worst possible moment -- both seeing the same old
     * last_seen_at and both granting the same gap.
     */
    private static final String GRANT_RECONNECT_TIME = """
            UPDATE attempts a
               SET a.end_time = DATE_ADD(a.end_time, INTERVAL LEAST(
                           TIMESTAMPDIFF(SECOND, a.last_seen_at, NOW()),
                           ? - a.extended_seconds) SECOND),
                   a.extended_seconds = a.extended_seconds + LEAST(
                       TIMESTAMPDIFF(SECOND, a.last_seen_at, NOW()),
                       ? - a.extended_seconds),
                   a.last_seen_at = NOW()
             WHERE a.id = ?
               AND a.status = 'STARTED'
               AND a.end_time IS NOT NULL
               AND a.last_seen_at IS NOT NULL
               AND TIMESTAMPDIFF(SECOND, a.last_seen_at, NOW()) >= ?
               AND a.extended_seconds < ?
            """;

    /**
     * Notes that this candidate's browser is still there.
     *
     * A single indexed row update, deliberately not routed through the entity:
     * loading and flushing an Attempt on every clock poll is what the attempt
     * cache exists to avoid. At a full hall polling every twenty seconds this
     * is around a hundred small writes a second, well inside what the database
     * already carries for answers alone.
     *
     * Failure is swallowed. A missed heartbeat is an invigilator seeing a stale
     * status for twenty seconds; an exception here would be a candidate's clock
     * poll failing, which is far worse.
     *
     * Returns true when this call gave back time lost to a disconnection.
     */
    public boolean markSeen(Long attemptId) {
        try {
            // The gap case first. Its WHERE matches only after a real silence,
            // so on the overwhelming majority of polls this touches nothing.
            int returned = jdbcTemplate.update(GRANT_RECONNECT_TIME,
                    reconnectMaxSeconds, reconnectMaxSeconds,
                    attemptId, reconnectGraceSeconds, reconnectMaxSeconds);

            if (returned > 0) {
                // The deadline just moved, and the clock is served from a cache
                // that does not know. Without this the candidate is given the
                // time and then never sees it.
                attemptCache.invalidate(attemptId);
                log.info("Attempt {} reconnected after a gap; exam time returned.", attemptId);
                return true;
            }

            jdbcTemplate.update(
                    "UPDATE attempts SET last_seen_at = NOW() WHERE id = ? AND status <> 'SUBMITTED'",
                    attemptId);
        } catch (RuntimeException e) {
            log.debug("Could not stamp last_seen_at for attempt {}: {}", attemptId, e.toString());
        }
        return false;
    }

    /**
     * Whether a started paper could still be written: its deadline has not
     * passed, or has passed by less than the time a dropped connection could
     * still be given back. Read-only -- it neither stamps the heartbeat nor
     * grants anything; Start does both.
     */
    public boolean couldStillWrite(Attempt attempt, LocalDateTime now) {
        if (attempt.getEndTime() == null) return false;
        long owedAtMost = Math.max(0, reconnectMaxSeconds - attempt.getExtendedSeconds());
        return now.isBefore(attempt.getEndTime().plusSeconds(owedAtMost));
    }

    public long getRemainingSeconds(Long attemptId) {
        AttemptCache.AttemptMeta meta = attemptCache.get(attemptId);

        // No deadline yet means the attempt has not been started.
        if (meta.endTime() == null) return 0;

        long remaining = Duration.between(LocalDateTime.now(), meta.endTime()).getSeconds();
        if (remaining <= 0) {
            // Expired: the grader opens its own transaction. Idempotent, so a burst
            // of simultaneous expiry polls still grades exactly once.
            scoringService.submit(attemptId, "auto-submit: time expired");
            return 0;
        }
        return remaining;
    }

    public Attempt submitAttempt(Long attemptId, String reason) {
        return scoringService.submit(attemptId, reason);
    }

    public ResultResponse getResult(Long attemptId) {
        return scoringService.buildResult(attemptId);
    }
}
