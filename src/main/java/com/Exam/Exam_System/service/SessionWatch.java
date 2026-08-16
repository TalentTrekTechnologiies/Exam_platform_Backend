package com.Exam.Exam_System.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects one hall ticket being written from two devices at the same time.
 *
 * A candidate signing in more than once is completely normal — a crashed
 * machine, a power cut, a move to a spare PC. So a new session on its own
 * proves nothing, and treating it as cheating would punish exactly the people
 * the resume feature exists to protect.
 *
 * What is NOT normal is two sessions writing to one attempt in the same few
 * minutes: that is one candidate's credentials in two people's hands, answering
 * in parallel. The distinction is interleaving, not novelty — so this only
 * flags when the previous session was active recently AND the sessions keep
 * alternating.
 *
 * In memory on purpose. The signal is inherently short-lived (it only means
 * anything within a live sitting), and paying a database write on every single
 * answer save — the hottest path in the system, measured at 5,000 concurrent
 * candidates — to store something this ephemeral would be a poor trade. The
 * violations it raises ARE persisted; only the tracking state is transient.
 */
@Service
public class SessionWatch {

    private static final Logger log = LoggerFactory.getLogger(SessionWatch.class);

    /**
     * How recently the other session must have been seen for an alternation to
     * look concurrent rather than sequential. Deliberately generous: a candidate
     * moving to a spare machine takes a minute or two, and that must never be
     * flagged. Two people answering in parallel alternate far faster than this.
     */
    private static final long CONCURRENT_WINDOW_MS = 90_000;

    /**
     * A single switch is a resume. Repeated alternation is two people taking
     * turns. Requiring more than one crossing is what keeps the false-positive
     * rate near zero.
     */
    private static final int ALTERNATIONS_BEFORE_FLAGGING = 2;

    /** Bounded like AttemptCache — a long-running server must not grow forever. */
    private static final int MAX_TRACKED = 250_000;

    private record Seen(String session, long at, int alternations, boolean flagged) {}

    private final ConcurrentHashMap<Long, Seen> lastSeen = new ConcurrentHashMap<>();

    /**
     * Records activity and reports whether this attempt now looks like two
     * concurrent sessions.
     *
     * Returns true exactly once per attempt, so an invigilator gets one clear
     * flag rather than one per keystroke for the rest of the paper.
     */
    public boolean observe(Long attemptId, String session) {
        if (attemptId == null || session == null || session.isBlank()) return false;

        if (lastSeen.size() > MAX_TRACKED) {
            long cutoff = System.currentTimeMillis() - CONCURRENT_WINDOW_MS;
            lastSeen.entrySet().removeIf(e -> e.getValue().at() < cutoff);
        }

        long now = System.currentTimeMillis();
        final boolean[] raise = {false};

        lastSeen.compute(attemptId, (id, previous) -> {
            if (previous == null || previous.session().equals(session)) {
                // Same device carrying on, or the first thing we've seen.
                int carried = previous == null ? 0 : previous.alternations();
                boolean wasFlagged = previous != null && previous.flagged();
                return new Seen(session, now, carried, wasFlagged);
            }

            // A different session. Only interesting if the other one was active
            // moments ago — otherwise this is an ordinary resume.
            boolean overlapping = (now - previous.at()) < CONCURRENT_WINDOW_MS;
            int alternations = overlapping ? previous.alternations() + 1 : 0;

            if (!previous.flagged() && alternations >= ALTERNATIONS_BEFORE_FLAGGING) {
                raise[0] = true;
                log.warn("Attempt {} is being written from two sessions concurrently.", attemptId);
                return new Seen(session, now, alternations, true);
            }
            return new Seen(session, now, alternations, previous.flagged());
        });

        return raise[0];
    }

    /** Frees the tracking slot once an attempt is finished. */
    public void forget(Long attemptId) {
        if (attemptId != null) lastSeen.remove(attemptId);
    }

    Map<Long, ?> snapshotForTests() { return Map.copyOf(lastSeen); }
}
