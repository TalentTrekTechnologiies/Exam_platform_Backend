package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.repository.AttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the parts of an attempt that never change once it starts:
 * who it belongs to, which exam it is, and when it ends.
 *
 * This exists for one reason. The clock poll is the most-called endpoint in the
 * system — every candidate, every 20 seconds — and each poll previously cost two
 * database reads (one to prove ownership, one to read the deadline). At 100,000
 * candidates that is ~10,000 queries/sec spent re-reading values that cannot
 * have changed. Served from memory, it is ~0.
 *
 * Safe across a fleet without any coordination, precisely because the cached
 * fields are immutable. Mutable state — status, score — is deliberately NOT
 * cached, so every write still validates against the database.
 */
@Service
public class AttemptCache {

    private static final Logger log = LoggerFactory.getLogger(AttemptCache.class);

    /** Immutable identity and deadline of one attempt. */
    public record AttemptMeta(Long attemptId, Long studentId, Long examId, LocalDateTime endTime) {}

    private final AttemptRepository attemptRepository;
    private final ConcurrentHashMap<Long, AttemptMeta> cache = new ConcurrentHashMap<>();
    private final int maxEntries;

    public AttemptCache(AttemptRepository attemptRepository,
                        @Value("${app.cache.attempt-max-entries:250000}") int maxEntries) {
        this.attemptRepository = attemptRepository;
        this.maxEntries = maxEntries;
    }

    /** Cached metadata, loading from the database on first use. */
    public AttemptMeta get(Long attemptId) {
        AttemptMeta cached = cache.get(attemptId);
        if (cached != null) return cached;

        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));

        // An attempt that has not started yet has no deadline to cache; serve it
        // from the database until it does.
        if (attempt.getEndTime() == null) {
            return new AttemptMeta(attempt.getId(), attempt.getStudentId(), attempt.getExamId(), null);
        }

        AttemptMeta meta = new AttemptMeta(
                attempt.getId(), attempt.getStudentId(), attempt.getExamId(), attempt.getEndTime());

        // Bounded so a long-lived server across many exams cannot grow without
        // limit. Clearing is cheap and the entries simply reload on demand.
        if (cache.size() >= maxEntries) {
            log.info("Attempt cache reached {} entries — clearing.", maxEntries);
            cache.clear();
        }
        cache.put(attemptId, meta);
        return meta;
    }

    /**
     * Records that an attempt has finished, by moving its cached deadline to the
     * moment it was submitted. Subsequent clock polls then compute zero
     * remaining without touching the database.
     *
     * Across a fleet, another instance may briefly still hold the original
     * deadline and show a running clock for an already-submitted attempt. That
     * is cosmetic only — every write is validated against the database, so no
     * answer can land after submission regardless of which instance serves it.
     */
    public void markEnded(Long attemptId, LocalDateTime endedAt) {
        AttemptMeta existing = cache.get(attemptId);
        if (existing != null) {
            cache.put(attemptId, new AttemptMeta(
                    existing.attemptId(), existing.studentId(), existing.examId(), endedAt));
        }
    }

    /** Drops an entry once the attempt is finished with. */
    public void invalidate(Long attemptId) {
        cache.remove(attemptId);
    }

    public int size() {
        return cache.size();
    }
}
