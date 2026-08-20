package com.Exam.Exam_System.service.judge;

/**
 * Runs a candidate's program against one input and says what it produced.
 *
 * An interface with two implementations behind it, because the two situations
 * are genuinely different and pretending otherwise is how untrusted code ends
 * up running somewhere it shouldn't:
 *
 *   · {@link Judge0Judge} talks to a Judge0 container that does the isolation
 *     properly — no network, capped CPU and memory, killed on overrun. This is
 *     the only implementation fit for an exam.
 *
 *   · {@link LocalJudge} shells out to the compilers on this machine. It exists
 *     so the whole coding round can be built and tested without a container
 *     runtime, and it REFUSES to start unless someone has deliberately switched
 *     it on outside production.
 *
 * The distinction is the security boundary of the entire feature. A coding exam
 * means running strangers' programs on your own server; the question is never
 * whether that is risky but what stands between their code and everything else
 * on the machine.
 */
public interface Judge {

    /**
     * @param language  one of {@link Language}'s ids
     * @param source    the candidate's program, exactly as they wrote it
     * @param stdin     what to feed it, or null
     * @param timeLimitMs   wall clock, per run
     * @param memoryLimitMb address space
     */
    RunResult run(String language, String source, String stdin, int timeLimitMs, int memoryLimitMb);

    /** Whether this judge can currently accept work — surfaced before an exam. */
    default boolean available() { return true; }

    /** How this judge would describe itself in an admin health panel. */
    String describe();
}
