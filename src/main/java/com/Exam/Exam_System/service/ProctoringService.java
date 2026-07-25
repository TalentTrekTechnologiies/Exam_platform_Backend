package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.ExamViolation;
import com.Exam.Exam_System.repository.ExamViolationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists proctoring events so an invigilator can audit them after the exam.
 */
@Service
public class ProctoringService {

    /**
     * The event types that count toward ending an exam. Each is an unambiguous
     * act by the candidate — they left the exam window. Camera observations are
     * excluded by design; see StudentController.CAMERA_OBSERVATIONS.
     */
    private static final java.util.Set<String> STRIKE_TYPES =
            java.util.Set.of("FULLSCREEN_EXIT", "TAB_SWITCH", "APP_SWITCH");

    private static final Logger log = LoggerFactory.getLogger(ProctoringService.class);

    private final ExamViolationRepository violationRepository;
    private final AttemptCache attemptCache;

    public ProctoringService(ExamViolationRepository violationRepository, AttemptCache attemptCache) {
        this.violationRepository = violationRepository;
        this.attemptCache = attemptCache;
    }

    /** Records one event and returns the candidate's running total. */
    @Transactional
    public long record(Long attemptId, String type, Integer occurrence, String detail) {
        AttemptCache.AttemptMeta meta = attemptCache.get(attemptId);

        ExamViolation v = new ExamViolation();
        v.setAttemptId(attemptId);
        v.setStudentId(meta.studentId());
        v.setExamId(meta.examId());
        v.setType(type);
        v.setOccurrence(occurrence);
        // Trimmed: this is an audit note, not a channel for arbitrary payloads.
        v.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 300)));
        // Server time deliberately — the candidate's clock is not evidence.
        v.setOccurredAt(LocalDateTime.now());

        violationRepository.save(v);
        log.info("Proctoring: {} on attempt {} (occurrence {})", type, attemptId, occurrence);

        // Deliberately the STRIKE count, not every recorded event. Camera
        // observations are stored in full for an invigilator to review, but a
        // probabilistic "no face detected" must never push a real candidate
        // toward having their exam ended.
        return violationRepository.countByAttemptIdAndTypeIn(attemptId, STRIKE_TYPES);
    }

    @Transactional(readOnly = true)
    public List<ExamViolation> forAttempt(Long attemptId) {
        return violationRepository.findByAttemptIdOrderByOccurredAtAsc(attemptId);
    }
}
