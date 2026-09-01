package com.Exam.Exam_System.service.judge;

import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.TestCase;
import com.Exam.Exam_System.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The coding round, actually running code.
 *
 * Everything else about this feature can be checked by reading it. This cannot:
 * whether a candidate's program compiles, runs, has its output compared to the
 * key correctly, and earns the marks it should. So these tests drive the real
 * LocalJudge against the host's real Python, with real submissions — including
 * ones that are wrong in each of the ways a candidate's program is wrong.
 *
 * Skipped where Python is unavailable rather than failing, because a machine
 * without it is not a broken build. The judge that matters in production is
 * Judge0; LocalJudge shares the comparison and marking path with it, which is
 * the part these tests are here for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnabledIf("pythonAvailable")
class CodeExecutionServiceTest {

    @Mock TestCaseRepository testCases;

    CodeExecutionService execution;
    Question question;

    static boolean pythonAvailable() {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";
        try {
            Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sum the numbers on the second line. Deliberately trivial: the program is not the subject. */
    private static final String CORRECT =
            "n = int(input())\nnums = list(map(int, input().split()))\nprint(sum(nums))\n";

    @BeforeEach
    void setUp() {
        execution = new CodeExecutionService(new LocalJudge(), testCases);

        question = new Question();
        question.setType("CODING");
        question.setMarks(10);
        question.setTimeLimitMs(5000);
        question.setMemoryLimitMb(256);
        question.setAllowedLanguages("python");
        setId(question, 1L);
    }

    private static void setId(Object entity, long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestCase testCase(long id, String input, String expected, boolean sample, double weight) {
        TestCase t = new TestCase();
        t.setQuestionId(1L);
        t.setInput(input);
        t.setExpectedOutput(expected);
        t.setSample(sample);
        t.setWeight(weight);
        t.setDisplayOrder((int) id);
        setId(t, id);
        return t;
    }

    /** Two visible cases and two hidden ones — the shape of a real question. */
    private void givenFourCases() {
        List<TestCase> all = new ArrayList<>(List.of(
                testCase(1, "3\n1 2 3\n", "6", true, 1.0),
                testCase(2, "1\n5\n", "5", true, 1.0),
                testCase(3, "5\n10 20 30 40 50\n", "150", false, 1.0),
                testCase(4, "1\n0\n", "0", false, 1.0)
        ));
        when(testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(anyLong())).thenReturn(all);
        when(testCases.findByQuestionIdAndSampleTrueOrderByDisplayOrderAscIdAsc(anyLong()))
                .thenReturn(all.stream().filter(TestCase::isSample).toList());
    }

    // ── the happy path, which has never actually been run before ─────────────

    @Test
    @DisplayName("A correct program passes every case and earns the full marks")
    void correctSolutionScoresFull() {
        givenFourCases();

        CodeExecutionService.Judgement j = execution.judge(question, "python", CORRECT);

        assertEquals(4, j.total(), "every case is marked, hidden ones included");
        assertEquals(4, j.passed(), () -> "expected all to pass, got: " + j.message());
        assertEquals(10.0, j.marks(), 0.001, "full marks for a fully correct program");
    }

    @Test
    @DisplayName("A wrong program earns nothing, and is not marked below zero")
    void wrongSolutionScoresZero() {
        givenFourCases();

        // Not print(0): one of the cases legitimately expects 0, and a program
        // that happens to agree with a case has genuinely earned it. This one
        // is wrong on all four.
        CodeExecutionService.Judgement j = execution.judge(question, "python", "print(-1)\n");

        assertEquals(0.0, j.marks(), 0.001);
        assertTrue(j.marks() >= 0, "a coding question must never mark a candidate down");
    }

    @Test
    @DisplayName("Partial credit: a program right on half the cases earns half the marks")
    void partialCredit() {
        givenFourCases();

        // Correct except that it cannot handle a single element, failing case 2
        // and case 4 — the two that are exactly one number.
        String halfRight =
                "n = int(input())\nnums = list(map(int, input().split()))\n"
                + "print(sum(nums) if n > 1 else 999)\n";

        CodeExecutionService.Judgement j = execution.judge(question, "python", halfRight);

        assertEquals(2, j.passed(), () -> "expected 2 of 4: " + j.message());
        assertEquals(5.0, j.marks(), 0.001, "half the weight is half the marks");
    }

    @Test
    @DisplayName("Weights: an edge case can be worth more than a happy path")
    void weightsAreHonoured() {
        // The edge case carries three times the weight of the ordinary one.
        List<TestCase> all = new ArrayList<>(List.of(
                testCase(1, "3\n1 2 3\n", "6", true, 1.0),
                testCase(2, "1\n0\n", "0", false, 3.0)
        ));
        when(testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(anyLong())).thenReturn(all);

        // Passes the ordinary case, fails the edge case: 1 of 4 weight = 2.5.
        String wrongOnEdge =
                "n = int(input())\nnums = list(map(int, input().split()))\n"
                + "print(sum(nums) if sum(nums) > 0 else 42)\n";

        CodeExecutionService.Judgement j = execution.judge(question, "python", wrongOnEdge);

        assertEquals(1, j.passed());
        assertEquals(2.5, j.marks(), 0.001, "marks follow weight, not case count");
    }

    // ── the ways a candidate's program goes wrong ────────────────────────────

    @Test
    @DisplayName("A program that does not compile is reported, not crashed on")
    void syntaxErrorIsHandled() {
        givenFourCases();

        CodeExecutionService.Judgement j =
                execution.judge(question, "python", "print('unterminated\n");

        assertEquals(0.0, j.marks(), 0.001);
        assertNotNull(j.message(), "the candidate is told what went wrong");
        assertFalse(j.message().isBlank());
    }

    @Test
    @DisplayName("A program that throws at runtime scores zero without taking the judge down")
    void runtimeErrorIsHandled() {
        givenFourCases();

        CodeExecutionService.Judgement j =
                execution.judge(question, "python", "raise ValueError('boom')\n");

        assertEquals(0, j.passed());
        assertEquals(0.0, j.marks(), 0.001);
    }

    @Test
    @DisplayName("A program that never terminates is stopped by the time limit")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void infiniteLoopIsStopped() {
        givenFourCases();
        question.setTimeLimitMs(1500);

        /*
         * What is being proved is that this RETURNS. Without a limit it does
         * not, and in a real sitting that is one candidate's runaway loop
         * holding a worker while everybody else waits to submit.
         *
         * The bound is a JUnit timeout rather than a measured elapsed time on
         * purpose. The first version asserted under sixty seconds, which held
         * when this test ran on its own and failed when the rest of the suite
         * was competing for the same two cores — a test that fails because the
         * machine was busy teaches nobody anything, and gets muted.
         */
        CodeExecutionService.Judgement j = execution.judge(question, "python", "while True:\n    pass\n");

        assertEquals(0, j.passed(), "a program that never produces output passes nothing");
        assertEquals(0.0, j.marks(), 0.001);
    }

    // ── what a candidate is allowed to see ──────────────────────────────────

    @Test
    @DisplayName("Run touches only the sample cases — the hidden ones are not the candidate's to probe")
    void runUsesSamplesOnly() {
        givenFourCases();

        CodeExecutionService.Judgement j = execution.runSamples(question, "python", CORRECT);

        assertEquals(2, j.total(), "only the two samples are run");
        assertTrue(j.cases().stream().allMatch(CodeExecutionService.CaseResult::sample));
    }

    @Test
    @DisplayName("A hidden case never carries its input back — that input is the key")
    void hiddenCaseInputIsNotLeaked() {
        givenFourCases();

        CodeExecutionService.Judgement j = execution.judge(question, "python", CORRECT);

        for (CodeExecutionService.CaseResult c : j.cases()) {
            if (!c.sample()) {
                assertNull(c.input(),
                        "a hidden case's input reached the response; a candidate could read the key off it");
            }
        }
    }

    @Test
    @DisplayName("Trailing whitespace and a missing final newline do not fail a correct program")
    void outputComparisonIsForgivingOfWhitespace() {
        List<TestCase> all = new ArrayList<>(List.of(
                testCase(1, "3\n1 2 3\n", "6\n", true, 1.0)
        ));
        when(testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(anyLong())).thenReturn(all);

        // print() emits "6\n"; the key here has its own trailing newline, and a
        // candidate whose program is right must not lose marks to that.
        CodeExecutionService.Judgement j = execution.judge(question, "python", CORRECT);

        assertEquals(1, j.passed(), () -> "whitespace cost a correct program its marks: " + j.message());
    }

    @Test
    @DisplayName("A question with no test cases marks nothing rather than everything")
    void noTestCasesIsNotFullMarks() {
        when(testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(anyLong())).thenReturn(List.of());

        CodeExecutionService.Judgement j = execution.judge(question, "python", CORRECT);

        assertEquals(0, j.total());
        assertEquals(0.0, j.marks(), 0.001,
                "an unconfigured question must not hand out marks to everybody who submits");
    }
}
