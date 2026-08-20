package com.Exam.Exam_System.service.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compiles and runs candidate code with the compilers on this machine.
 *
 * FOR DEVELOPMENT AND TESTING ONLY. It is here so the coding round can be built
 * and verified end to end without a container runtime, and it must never carry
 * a real exam.
 *
 * What it does NOT do is exactly what makes it unfit: it does not put the
 * program in a jail. A submission runs as the application's own user, with the
 * application's own file access. On an exam server that means a candidate could
 * read the database password out of the environment, or write to the college's
 * website. It kills a program at the time limit and it refuses a few obvious
 * abuses, and neither of those is isolation.
 *
 * {@link JudgeConfig} refuses to start the application if this is selected
 * while a production profile is active — the same shape as the check that stops
 * the development JWT secret reaching production, and for the same reason: a
 * setting whose mistakes are silent needs to be loud somewhere.
 */
public class LocalJudge implements Judge {

    private static final Logger log = LoggerFactory.getLogger(LocalJudge.class);

    /** Anything a program should never do while answering a question. */
    private static final List<String> REFUSED = List.of(
            "Runtime.getRuntime", "ProcessBuilder", "System.exit",
            "java.net", "java.nio.file", "java.io.File",
            "import os", "import sys", "import socket", "import subprocess",
            "__import__", "eval(", "exec(", "open(",
            "#include <unistd.h>", "system(", "fork(", "popen(", "fopen("
    );

    @Override
    public String describe() {
        return "Local compilers on the application host (development only)";
    }

    @Override
    public RunResult run(String languageId, String source, String stdin,
                         int timeLimitMs, int memoryLimitMb) {

        Language language = Language.byId(languageId).orElse(null);
        if (language == null) return RunResult.judgeError("Unknown language: " + languageId);
        if (source == null || source.isBlank()) {
            return new RunResult(RunResult.Status.COMPILE_ERROR, "", "", "Empty submission.", 0, null);
        }

        // A blunt instrument, and no substitute for a sandbox — which is the
        // point. It exists so a careless moment in development does not delete
        // the developer's home directory.
        for (String banned : REFUSED) {
            if (source.contains(banned)) {
                return new RunResult(RunResult.Status.COMPILE_ERROR, "", "",
                        "The development judge refuses '" + banned
                                + "'. Run the real sandbox to execute this.", 0, null);
            }
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("judge-");
            Path file = dir.resolve(language.fileName());
            Files.writeString(file, language.normaliseSource(source), StandardCharsets.UTF_8);

            if (language == Language.C || language == Language.CPP) {
                return new RunResult(RunResult.Status.JUDGE_ERROR, "", "",
                        "C and C++ need the real sandbox; no compiler is available here.", 0, null);
            }

            if (language == Language.JAVA) {
                RunResult compiled = exec(dir, timeLimitMs + 10_000, null,
                        "javac", "-nowarn", file.getFileName().toString());
                if (compiled.status() != RunResult.Status.OK || (compiled.exitCode() != null && compiled.exitCode() != 0)) {
                    String diagnostics = (compiled.stderr() + compiled.stdout()).trim();
                    return new RunResult(RunResult.Status.COMPILE_ERROR, "", "",
                            diagnostics.isEmpty() ? "Compilation failed." : diagnostics, 0, null);
                }
                return exec(dir, timeLimitMs, stdin, "java", "-Xmx" + Math.max(16, memoryLimitMb) + "m", "Main");
            }

            return exec(dir, timeLimitMs, stdin, pythonCommand(), file.getFileName().toString());

        } catch (Exception e) {
            log.error("Local judge failed", e);
            return RunResult.judgeError(e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    private static String pythonCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";
    }

    private RunResult exec(Path dir, int timeLimitMs, String stdin, String... command) {
        long started = System.currentTimeMillis();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            // Nothing of the application's environment is handed to the program:
            // the database password lives there.
            pb.environment().clear();
            pb.environment().put("PATH", System.getenv("PATH") == null ? "" : System.getenv("PATH"));
            if (System.getenv("SystemRoot") != null) pb.environment().put("SystemRoot", System.getenv("SystemRoot"));

            process = pb.start();

            if (stdin != null && !stdin.isEmpty()) {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();

            boolean finished = process.waitFor(timeLimitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RunResult(RunResult.Status.TIMEOUT, "", "", "",
                        System.currentTimeMillis() - started, null);
            }

            String out = read(process.getInputStream());
            String err = read(process.getErrorStream());
            int exit = process.exitValue();
            long took = System.currentTimeMillis() - started;

            if (exit != 0) {
                boolean oom = err.contains("OutOfMemoryError") || err.contains("MemoryError");
                return new RunResult(oom ? RunResult.Status.MEMORY_EXCEEDED : RunResult.Status.RUNTIME_ERROR,
                        out, err, "", took, exit);
            }
            return new RunResult(RunResult.Status.OK, out, err, "", took, exit);

        } catch (IOException e) {
            return RunResult.judgeError(command[0] + " is not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunResult.judgeError("Interrupted");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(java.io.InputStream in) throws IOException {
        // Capped: a program printing forever must not become the server's
        // memory problem.
        byte[] buffer = in.readNBytes(256 * 1024);
        return new String(buffer, StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // A temp directory that outlives the run is untidy, not broken.
        }
    }

    /** Languages this judge can actually carry, for an honest admin panel. */
    public static List<Language> supported() {
        return new ArrayList<>(List.of(Language.JAVA, Language.PYTHON));
    }
}
