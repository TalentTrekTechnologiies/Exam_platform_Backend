package com.Exam.Exam_System.service.judge;

/**
 * What happened when a candidate's program ran once.
 *
 * The status matters as much as the output. A candidate whose program was
 * killed at the time limit and one whose program printed the wrong answer have
 * both failed the case, but only one of them has a bug in their logic — and the
 * screen should not tell them the same thing.
 */
public record RunResult(
        Status status,
        String stdout,
        String stderr,
        /** Compiler diagnostics. The single most useful thing a candidate can be shown. */
        String compileOutput,
        long timeMs,
        Integer exitCode
) {

    public enum Status {
        /** Ran to completion. Whether it is RIGHT is for the caller to compare. */
        OK,
        /** Did not compile. Never the candidate's fault to guess at — show the output. */
        COMPILE_ERROR,
        /** Killed at the time limit. */
        TIMEOUT,
        /** Exceeded the memory ceiling. */
        MEMORY_EXCEEDED,
        /** Crashed: a non-zero exit, a segfault, an uncaught exception. */
        RUNTIME_ERROR,
        /** The judge itself failed. Never counted against the candidate. */
        JUDGE_ERROR
    }

    public boolean ran() { return status == Status.OK; }

    public static RunResult judgeError(String message) {
        return new RunResult(Status.JUDGE_ERROR, "", message, "", 0, null);
    }

    /** What a candidate should be told, in words rather than an enum. */
    public String humanStatus() {
        return switch (status) {
            case OK -> "Ran";
            case COMPILE_ERROR -> "Compilation error";
            case TIMEOUT -> "Time limit exceeded";
            case MEMORY_EXCEEDED -> "Memory limit exceeded";
            case RUNTIME_ERROR -> "Runtime error";
            case JUDGE_ERROR -> "Could not be run — tell your invigilator";
        };
    }
}
