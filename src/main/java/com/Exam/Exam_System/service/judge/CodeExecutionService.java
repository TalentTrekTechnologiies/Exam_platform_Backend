package com.Exam.Exam_System.service.judge;

import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.TestCase;
import com.Exam.Exam_System.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running a candidate's program against a question's test cases, and turning
 * the result into marks.
 *
 * Two entry points, deliberately different:
 *
 *   · {@link #runSamples} is "Compile & Run" — the visible cases only, with
 *     their inputs and both outputs shown. Nothing is stored and nothing is
 *     marked; it is the candidate checking their program reads and writes in
 *     the shape the problem asked for.
 *
 *   · {@link #judge} is the submission. Every case, sample and hidden, and the
 *     hidden ones' inputs never leave this method.
 */
@Service
public class CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);

    private static final int DEFAULT_TIME_LIMIT_MS = 2000;
    private static final int DEFAULT_MEMORY_LIMIT_MB = 256;

    private final Judge judge;
    private final TestCaseRepository testCases;

    public CodeExecutionService(Judge judge, TestCaseRepository testCases) {
        this.judge = judge;
        this.testCases = testCases;
    }

    public boolean judgeAvailable() { return judge.available(); }

    public String judgeDescription() { return judge.describe(); }

    /** One case's verdict, as the candidate or the marker sees it. */
    public record CaseResult(
            Long testCaseId,
            String label,
            boolean sample,
            boolean passed,
            String status,
            /** Null for a hidden case — its input is the thing being protected. */
            String input,
            String expected,
            String actual,
            String stderr,
            long timeMs
    ) {}

    /** Everything a submission earned. */
    public record Judgement(
            int passed,
            int total,
            double marks,
            String message,
            List<CaseResult> cases
    ) {}

    /**
     * Compile & Run: the sample cases, shown in full.
     *
     * A compile error short-circuits the whole thing — there is no point
     * running four cases against a program that did not build, and the
     * compiler's message is the single most useful thing the candidate can be
     * given.
     */
    public Judgement runSamples(Question question, String language, String source) {
        List<TestCase> samples = testCases.findByQuestionIdAndSampleTrueOrderByDisplayOrderAscIdAsc(question.getId());

        if (samples.isEmpty()) {
            // Fall back to the worked example on the question itself, so a
            // setter who wrote one but no sample case still gives the candidate
            // something to run against.
            if (question.getSampleInput() != null || question.getSampleOutput() != null) {
                TestCase fromStatement = new TestCase();
                fromStatement.setInput(question.getSampleInput());
                fromStatement.setExpectedOutput(question.getSampleOutput());
                fromStatement.setSample(true);
                fromStatement.setLabel("Example");
                samples = List.of(fromStatement);
            } else {
                return new Judgement(0, 0, 0,
                        "This question has no example to run against. Submit when you are ready.", List.of());
            }
        }

        return execute(question, language, source, samples, true);
    }

    /** The submission: every case, hidden ones included, and the marks it earns. */
    public Judgement judge(Question question, String language, String source) {
        List<TestCase> all = testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(question.getId());
        if (all.isEmpty()) {
            return new Judgement(0, 0, 0,
                    "This question has no test cases, so it cannot be marked automatically.", List.of());
        }
        return execute(question, language, source, all, false);
    }

    private Judgement execute(Question question, String language, String source,
                              List<TestCase> cases, boolean reveal) {

        int timeLimit = question.getTimeLimitMs() == null ? DEFAULT_TIME_LIMIT_MS : question.getTimeLimitMs();
        int memoryLimit = question.getMemoryLimitMb() == null ? DEFAULT_MEMORY_LIMIT_MB : question.getMemoryLimitMb();

        List<CaseResult> results = new ArrayList<>();
        double earnedWeight = 0, totalWeight = 0;
        int passed = 0;
        String message = null;

        for (TestCase testCase : cases) {
            totalWeight += testCase.getWeight();

            RunResult run = judge.run(language, source, testCase.getInput(), timeLimit, memoryLimit);

            if (run.status() == RunResult.Status.COMPILE_ERROR) {
                // The same failure for every case; report it once and stop.
                return new Judgement(0, cases.size(), 0,
                        blankToNull(run.compileOutput()) != null ? run.compileOutput() : "Your program did not compile.",
                        List.of(new CaseResult(testCase.getId(), testCase.getLabel(), testCase.isSample(),
                                false, "Compilation error", reveal ? testCase.getInput() : null,
                                reveal ? testCase.getExpectedOutput() : null, "", run.compileOutput(), 0)));
            }

            if (run.status() == RunResult.Status.JUDGE_ERROR) {
                log.error("Judge failed on question {}: {}", question.getId(), run.stderr());
                message = "Some cases could not be run. Tell your invigilator.";
            }

            boolean ok = run.ran() && matches(run.stdout(), testCase.getExpectedOutput());
            if (ok) { passed++; earnedWeight += testCase.getWeight(); }

            results.add(new CaseResult(
                    testCase.getId(),
                    testCase.getLabel(),
                    testCase.isSample(),
                    ok,
                    ok ? "Passed" : run.ran() ? "Wrong answer" : run.humanStatus(),
                    reveal ? testCase.getInput() : null,
                    reveal ? testCase.getExpectedOutput() : null,
                    reveal ? run.stdout() : null,
                    reveal ? run.stderr() : null,
                    run.timeMs()));
        }

        // Partial marks, weighted by case. A solution that handles seven cases
        // of ten has not simply got it wrong, and TCS NQT marks it that way.
        int questionMarks = question.getMarks() == null ? 1 : question.getMarks();
        double marks = totalWeight <= 0 ? 0 : questionMarks * (earnedWeight / totalWeight);

        return new Judgement(passed, cases.size(), round(marks),
                message != null ? message : summarise(passed, cases.size()), results);
    }

    private static String summarise(int passed, int total) {
        if (total == 0) return "Nothing to run.";
        if (passed == total) return "All " + total + " test cases passed.";
        if (passed == 0) return "No test cases passed.";
        return passed + " of " + total + " test cases passed.";
    }

    /**
     * Compares output the way a marker would, not the way a byte comparison
     * would.
     *
     * Trailing newlines, trailing spaces at the end of a line, and whether the
     * file ends with a newline are printing conventions, not answers. A
     * candidate whose logic is right should not lose the marks because
     * println added a newline the expected output did not have. Everything
     * else — including blank lines in the middle and spacing WITHIN a line — is
     * still significant.
     */
    static boolean matches(String actual, String expected) {
        return normalise(actual).equals(normalise(expected));
    }

    private static String normalise(String s) {
        if (s == null) return "";
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) last--;      // drop trailing blank lines
        for (int i = 0; i <= last; i++) {
            if (i > 0) out.append('\n');
            out.append(stripTrailing(lines[i]));
        }
        return out.toString();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** The languages this installation can actually run, for the editor's dropdown. */
    public List<Map<String, String>> languages(Question question) {
        List<Map<String, String>> out = new ArrayList<>();
        String allowed = question == null ? null : question.getAllowedLanguages();

        for (Language language : Language.all()) {
            if (allowed != null && !allowed.isBlank()) {
                boolean permitted = java.util.Arrays.stream(allowed.split(","))
                        .map(String::trim)
                        .anyMatch(a -> a.equalsIgnoreCase(language.id()));
                if (!permitted) continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", language.id());
            entry.put("label", language.label());
            out.add(entry);
        }
        return out;
    }
}
