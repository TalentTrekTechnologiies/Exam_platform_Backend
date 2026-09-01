package com.Exam.Exam_System.service.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often one candidate may ask the judge to run their code.
 *
 * Compile & Run is the only thing in the platform where a candidate can spend
 * the server's CPU at will, and they can spend a lot of it: a compile is
 * order-seconds, and nothing stopped somebody holding the button. One candidate
 * doing that occupies a worker continuously, and every other candidate in the
 * hall waits behind them.
 *
 * That is not a hypothetical at exam scale. A round of two thousand candidates
 * pressing Run ten times each is forty thousand compilations; without a floor
 * under the interval, a handful of people hammering it decides how long
 * everybody else waits.
 *
 * The limit is deliberately loose. It is not there to ration a legitimate
 * candidate — five seconds between runs is below what it takes to read a
 * failure and change a line — it is there so that the worst case is bounded.
 *
 * Held in memory, so with more than one app instance a candidate gets one
 * allowance per instance. That is an acceptable inaccuracy for a throttle: the
 * point is the bound, and being off by a factor of the instance count still
 * bounds it. Anything stricter would want Redis, and Judge0 already has one.
 */
@Service
public class RunThrottle {

    private static final Logger log = LoggerFactory.getLogger(RunThrottle.class);

    /** Below what it takes to read a compiler error and change a line. */
    @Value("${judge.run.min-interval-seconds:5}")
    private int minIntervalSeconds;

    /**
     * A ceiling on runs per attempt for the whole sitting.
     *
     * Generous on purpose — a candidate genuinely iterating on two problems
     * will not come close. It exists for the pathological case: a script, a
     * stuck key, or somebody who has worked out that Run is free.
     */
    @Value("${judge.run.max-per-attempt:400}")
    private int maxPerAttempt;

    private final Map<Long, Instant> lastRun = new ConcurrentHashMap<>();
    private final Map<Long, Integer> runCount = new ConcurrentHashMap<>();

    /** Raised when a candidate is asking faster than the floor allows. */
    public static class TooSoonException extends RuntimeException {
        private final long secondsToWait;

        public TooSoonException(String message, long secondsToWait) {
            super(message);
            this.secondsToWait = secondsToWait;
        }

        public long getSecondsToWait() { return secondsToWait; }
    }

    /**
     * Records a run, or refuses it.
     *
     * Called before the judge is asked for anything, so a refused run costs no
     * CPU at all — which is the entire point.
     */
    public void recordOrRefuse(Long attemptId) {
        int count = runCount.merge(attemptId, 1, Integer::sum);
        if (count > maxPerAttempt) {
            log.warn("Attempt {} has run code {} times; refusing further runs.", attemptId, count);
            throw new TooSoonException(
                    "You have run your code the maximum number of times for this exam."
                    + " You can still submit your answer.", 0);
        }

        Instant now = Instant.now();
        Instant previous = lastRun.put(attemptId, now);
        if (previous == null) return;

        long since = Duration.between(previous, now).getSeconds();
        if (since < minIntervalSeconds) {
            // Put the earlier timestamp back: a refused run must not restart
            // the clock, or holding the button would keep it permanently
            // refused and the candidate would never get their next turn.
            lastRun.put(attemptId, previous);
            long wait = minIntervalSeconds - since;
            throw new TooSoonException(
                    "Please wait " + wait + " second" + (wait == 1 ? "" : "s") + " before running again.",
                    wait);
        }
    }

    /** Frees an attempt's counters once its paper is done. */
    public void forget(Long attemptId) {
        lastRun.remove(attemptId);
        runCount.remove(attemptId);
    }
}
