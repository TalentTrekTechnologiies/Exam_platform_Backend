package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.ExamStudent;
import com.Exam.Exam_System.Entity.Slot;
import com.Exam.Exam_System.dto.ResultResponse;
import com.Exam.Exam_System.repository.AttemptRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
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

    @PersistenceContext
    private EntityManager entityManager;

    public AttemptService(AttemptRepository attemptRepository,
                          ExamRepository examRepository,
                          SlotRepository slotRepository,
                          PaperService paperService,
                          ScoringService scoringService,
                          AttemptCache attemptCache,
                          ExamStudentRepository examStudentRepository,
                          JdbcTemplate jdbcTemplate) {
        this.attemptRepository = attemptRepository;
        this.examRepository = examRepository;
        this.slotRepository = slotRepository;
        this.paperService = paperService;
        this.scoringService = scoringService;
        this.attemptCache = attemptCache;
        this.examStudentRepository = examStudentRepository;
        this.jdbcTemplate = jdbcTemplate;
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

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusMinutes(durationMinutes);

        // A candidate who logs in 20 minutes before the slot closes does not get
        // the full paper duration — the slot window is the hard ceiling.
        Slot slot = slotId == null ? null : slotRepository.findById(slotId).orElse(null);
        if (slot != null && slot.getEndTime() != null && endTime.isAfter(slot.getEndTime())) {
            endTime = slot.getEndTime();
            log.info("Attempt for student {} capped to slot end {}.", studentId, endTime);
        }

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
        return attempt;
    }

    /**
     * Starts the clock on an attempt whose paper is already frozen.
     *
     * One statement does the whole job: it reads the exam's duration, caps the
     * deadline at the slot's close, and flips the status — atomically, and only
     * if the attempt is still PENDING, so two simultaneous clicks cannot double
     * start it. This is the hot path at slot-open, when thousands of candidates
     * hit Start in the same second, so it is deliberately one round trip rather
     * than the four lookups it replaces.
     */
    private static final String ACTIVATE = """
            UPDATE attempts a
              JOIN exams e ON e.id = a.exam_id
              LEFT JOIN exam_slots s ON s.id = a.slot_id
               SET a.status     = 'STARTED',
                   a.start_time = NOW(),
                   a.end_time   = LEAST(
                       DATE_ADD(NOW(), INTERVAL e.duration MINUTE),
                       COALESCE(s.end_time, DATE_ADD(NOW(), INTERVAL e.duration MINUTE))
                   )
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
    public Map<String, Object> prepareAttempts(Long examId) {
        List<ExamStudent> mappings = examStudentRepository.findByExamId(examId);

        int prepared = 0;
        int alreadyReady = 0;

        for (ExamStudent mapping : mappings) {
            Attempt existing = attemptRepository
                    .findByStudentIdAndExamId(mapping.getStudentId(), examId).orElse(null);
            if (existing != null) {
                paperService.buildPaper(existing.getId(), examId); // fills any gap
                alreadyReady++;
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

        log.info("Prepared {} papers for exam {} ({} already ready).", prepared, examId, alreadyReady);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("examId", examId);
        report.put("candidates", mappings.size());
        report.put("prepared", prepared);
        report.put("alreadyReady", alreadyReady);
        report.put("summary", prepared == 0
                ? "All " + mappings.size() + " papers were already prepared."
                : "Prepared " + prepared + " paper(s); " + alreadyReady + " already ready.");
        return report;
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
