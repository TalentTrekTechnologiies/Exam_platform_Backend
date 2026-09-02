package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Answer;
import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.Entity.TestCase;
import com.Exam.Exam_System.repository.AnswerRepository;
import com.Exam.Exam_System.repository.QuestionRepository;
import com.Exam.Exam_System.repository.TestCaseRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.service.judge.CodeExecutionService;
import com.Exam.Exam_System.service.SectionClockService;
import com.Exam.Exam_System.service.judge.Language;
import com.Exam.Exam_System.service.judge.RunThrottle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The coding round.
 *
 * Two audiences on one controller because they are two halves of one feature,
 * and keeping them together is what stops the hidden test cases leaking: every
 * candidate-facing response in this file is assembled by hand, field by field,
 * rather than returning an entity that might one day gain a field nobody
 * remembered was secret.
 */
@RestController
public class CodingController {

    private final QuestionRepository questions;
    private final TestCaseRepository testCases;
    private final AnswerRepository answers;
    private final AccessGuard accessGuard;
    private final CodeExecutionService execution;

    private final RunThrottle runThrottle;
    private final SectionClockService sectionClock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public CodingController(QuestionRepository questions,
                            TestCaseRepository testCases,
                            AnswerRepository answers,
                            AccessGuard accessGuard,
                            CodeExecutionService execution,
                            RunThrottle runThrottle,
                            SectionClockService sectionClock,
                            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.questions = questions;
        this.testCases = testCases;
        this.answers = answers;
        this.accessGuard = accessGuard;
        this.execution = execution;
        this.runThrottle = runThrottle;
        this.sectionClock = sectionClock;
        this.jdbc = jdbc;
    }

    // ── Staff: writing the problem ──────────────────────────────────────────

    /** Whether this installation can run code at all, and in which languages. */
    @GetMapping("/admin/coding/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", execution.judgeAvailable());
        out.put("judge", execution.judgeDescription());
        out.put("languages", execution.languages(null));
        return out;
    }

    /**
     * The test cases for a question, in full — inputs, expected outputs and
     * which are hidden. Staff only; this is the answer key.
     */
    @GetMapping("/admin/coding/{questionId}/tests")
    public List<TestCase> listTests(@PathVariable Long questionId) {
        accessGuard.requireOwnedQuestion(questionId);
        return testCases.findByQuestionIdOrderByDisplayOrderAscIdAsc(questionId);
    }

    /**
     * Replaces the whole set in one go.
     *
     * Whole-set rather than one at a time because that is how a paper is
     * actually written: the setter pastes the cases in, reads them back, and
     * fixes them together. Editing them individually invites a half-updated
     * key, which is the one state that must never exist.
     */
    /*
     * Transactional for two reasons, and the first one bit immediately.
     *
     * A derived deleteBy query loads the rows and removes them one by one, and
     * removing needs a transaction. Without this the delete threw the moment
     * there was anything to delete -- so creating a question worked (nothing to
     * remove yet) and every edit afterwards failed, leaving the OLD cases in
     * place while the screen showed an error. An admin correcting a wrong
     * expected output would have been told it failed, and been right.
     *
     * The second reason outlives the first: this replaces the answer key in two
     * steps, and a failure between them would leave a question with no test
     * cases at all. Delete and insert belong in the same transaction.
     */
    @Transactional
    @PutMapping("/admin/coding/{questionId}/tests")
    public Map<String, Object> replaceTests(@PathVariable Long questionId,
                                            @RequestBody List<Map<String, Object>> incoming) {
        accessGuard.requireOwnedQuestion(questionId);

        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("A coding question needs at least one test case.");
        }

        List<TestCase> saved = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> row : incoming) {
            TestCase tc = new TestCase();
            tc.setQuestionId(questionId);
            tc.setInput(str(row.get("input")));
            tc.setExpectedOutput(str(row.get("expectedOutput")));
            tc.setSample(Boolean.TRUE.equals(row.get("sample")) || "true".equals(String.valueOf(row.get("sample"))));
            tc.setLabel(str(row.get("label")));
            Object weight = row.get("weight");
            tc.setWeight(weight == null ? 1.0 : Double.parseDouble(String.valueOf(weight)));
            tc.setDisplayOrder(order++);
            saved.add(tc);
        }

        if (saved.stream().noneMatch(TestCase::isSample)) {
            throw new IllegalArgumentException(
                    "At least one case must be a sample, or candidates have nothing to run against.");
        }

        testCases.deleteByQuestionId(questionId);
        testCases.saveAll(saved);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved.size());
        out.put("samples", saved.stream().filter(TestCase::isSample).count());
        out.put("hidden", saved.stream().filter(t -> !t.isSample()).count());
        out.put("summary", saved.size() + " test case(s) saved.");
        return out;
    }

    /**
     * Runs the setter's own reference solution against every case before the
     * paper goes anywhere near a candidate.
     *
     * This is the check worth having. A test case whose expected output is
     * wrong marks a correct program as failed, and nobody finds out until
     * results are published and the appeals begin.
     */
    @PostMapping("/admin/coding/{questionId}/verify")
    public Map<String, Object> verifyReferenceSolution(@PathVariable Long questionId,
                                                       @RequestBody Map<String, String> body) {
        Question question = accessGuard.requireOwnedQuestion(questionId);
        String language = body.get("language");
        String source = body.get("sourceCode");

        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Paste a reference solution to check the test cases against.");
        }

        // Revealing: this is the setter's own key, and the entire purpose of the
        // screen is to show which case disagrees and what it produced instead.
        CodeExecutionService.Judgement judgement = execution.verify(question, language, source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", judgement.passed());
        out.put("total", judgement.total());
        out.put("allPassed", judgement.total() > 0 && judgement.passed() == judgement.total());
        out.put("message", judgement.total() > 0 && judgement.passed() == judgement.total()
                ? "Every case agrees with this solution."
                : "Some cases disagree with this solution — check the expected outputs.");
        // Staff may see everything: it is their key.
        out.put("cases", judgement.cases());
        return out;
    }

    // ── Candidates: answering ───────────────────────────────────────────────

    /**
     * Compile & Run.
     *
     * The sample cases only. Nothing is stored, nothing is marked, and the
     * hidden cases are not touched — a candidate must not be able to discover
     * the key by running against it repeatedly.
     */
    @PostMapping("/student/code/run")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        Attempt attempt = accessGuard.requireOwnAttemptEntity(asLong(body.get("attemptId")));
        if ("SUBMITTED".equals(attempt.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "This exam has already been submitted."));
        }

        Question question = questionOnPaper(attempt, asLong(body.get("questionId")));
        String language = str(body.get("language"));
        String source = str(body.get("sourceCode"));

        if (source == null || source.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "passed", 0, "total", 0, "cases", List.of(),
                    "message", "Write some code first."));
        }

        /*
         * Refused before the judge is asked for anything, so a run that is too
         * soon costs no CPU at all. Compile & Run is the only place a candidate
         * can spend the server's processor at will, and at exam scale a handful
         * of people holding the button decides how long everybody else waits.
         */
        attempt = sectionClock.advanceIfDue(attempt);
        if (!sectionClock.isOpen(attempt, question.getSectionId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "This section has ended."));
        }

        try {
            runThrottle.recordOrRefuse(attempt.getId());
        } catch (RunThrottle.TooSoonException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", e.getMessage(), "retryAfterSeconds", e.getSecondsToWait()));
        }

        CodeExecutionService.Judgement judgement = execution.runSamples(question, language, source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", judgement.passed());
        out.put("total", judgement.total());
        out.put("message", judgement.message());
        out.put("cases", judgement.cases());
        // Deliberately absent: marks. Running is not submitting, and showing a
        // score here would tell a candidate their standing on the sample cases
        // as though it were their result.
        return ResponseEntity.ok(out);
    }

    /**
     * Submitting an answer to a coding question.
     *
     * Marked immediately against every case and stored with the code, so the
     * paper is already graded when the candidate presses Submit on the exam
     * itself — five thousand submissions all being judged at the final whistle
     * is the one load this design must not create.
     *
     * The candidate is told how many cases passed and what they earned. They
     * are never told WHICH hidden cases failed, because that is the key.
     */
    @PostMapping("/student/code/submit")
    public ResponseEntity<?> submitCode(@RequestBody Map<String, Object> body) {
        Attempt attempt = accessGuard.requireOwnAttemptEntity(asLong(body.get("attemptId")));
        if ("SUBMITTED".equals(attempt.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "This exam has already been submitted."));
        }

        Long questionId = asLong(body.get("questionId"));
        Question question = questionOnPaper(attempt, questionId);
        String language = str(body.get("language"));
        String source = str(body.get("sourceCode"));

        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("There is nothing to submit.");
        }

        /*
         * A coding section that has closed is closed to code.
         *
         * The multiple-choice path checks this and this one did not, which left
         * the whole sectional lock with a hole in it exactly where it matters
         * most: the coding round is the section most worth stealing time for,
         * and a candidate who kept the page open could have gone on submitting
         * into it after their time was up.
         */
        attempt = sectionClock.advanceIfDue(attempt);
        if (!sectionClock.isOpen(attempt, question.getSectionId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "This section has ended. Your last submission stands."));
        }

        CodeExecutionService.Judgement judgement = execution.judge(question, language, source);

        Answer answer = answers.findByAttemptIdAndQuestionId(attempt.getId(), questionId);
        if (answer == null) {
            answer = new Answer();
            answer.setAttemptId(attempt.getId());
            answer.setQuestionId(questionId);
        }

        answer.setSourceCode(source);
        answer.setLanguage(language);
        answer.setTestsPassed(judgement.passed());
        answer.setTestsTotal(judgement.total());
        answer.setAwardedMarks(judgement.marks());
        answer.setJudgeMessage(judgement.message());
        answer.setIsCorrect(judgement.total() > 0 && judgement.passed() == judgement.total());
        answer.setUpdatedAt(LocalDateTime.now());
        // Marks the question as attempted for the palette, which keys off this.
        answer.setSelectedOption(language == null ? "code" : language);
        answers.save(answer);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", true);
        out.put("passed", judgement.passed());
        out.put("total", judgement.total());
        out.put("marks", judgement.marks());
        out.put("message", judgement.message());
        // Sample cases only. The hidden ones are counted, never shown.
        out.put("cases", judgement.cases().stream().filter(CodeExecutionService.CaseResult::sample).toList());
        return ResponseEntity.ok(out);
    }

    /** The languages this question may be answered in. */
    @GetMapping("/student/code/languages/{questionId}")
    public List<Map<String, String>> languages(@PathVariable Long questionId) {
        Question question = questions.findById(questionId)
                .orElseThrow(() -> new NoSuchElementException("Question not found"));
        return execution.languages(question);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The question, confirmed to be on THIS candidate's paper and to be a
     * coding question.
     *
     * Without the first check a candidate could run code against any question
     * in the database by guessing an id; without the second they could aim the
     * judge at an MCQ and get nothing useful, which is harmless but confusing.
     */
    private Question questionOnPaper(Attempt attempt, Long questionId) {
        if (questionId == null) throw new IllegalArgumentException("No question given.");

        Question question = questions.findById(questionId)
                .orElseThrow(() -> new NoSuchElementException("Question not found"));

        if (!Objects.equals(question.getExamId(), attempt.getExamId())) {
            throw new IllegalArgumentException("That question is not on your paper.");
        }
        if (!question.isCoding()) {
            throw new IllegalArgumentException("That question is not a coding question.");
        }
        return question;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) throw new IllegalArgumentException("Missing id.");
        return Long.valueOf(String.valueOf(o));
    }

    /**
     * The programs candidates actually submitted, for one exam.
     *
     * A score on its own is not reviewable. "Why did I get four marks" has no
     * answer without the code that earned them, an appeal cannot be judged, and
     * a member of staff who suspects two candidates submitted the same solution
     * has nothing to compare. The platform stored every submission from the
     * start and gave nobody any way to read it.
     *
     * Staff only, scoped to an exam this admin owns.
     */
    @GetMapping("/admin/coding/exam/{examId}/submissions")
    public List<Map<String, Object>> submissionsFor(@PathVariable Long examId,
                                                    @RequestParam(required = false) Long questionId) {
        accessGuard.requireOwnedExam(examId);

        String sql = """
                SELECT s.hall_ticket, s.name AS candidate,
                       q.id AS question_id, q.question_text,
                       a.language, a.source_code, a.tests_passed, a.tests_total,
                       a.awarded_marks, a.judge_message, a.updated_at
                  FROM answers a
                  JOIN attempts  at ON at.id = a.attempt_id
                  JOIN students  s  ON s.id  = at.student_id
                  JOIN questions q  ON q.id  = a.question_id
                 WHERE at.exam_id = ?
                   AND q.type = 'CODING'
                   AND a.source_code IS NOT NULL
                   AND (? IS NULL OR q.id = ?)
                 ORDER BY q.id, s.hall_ticket
                """;

        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hallTicket", rs.getString("hall_ticket"));
            row.put("candidate", rs.getString("candidate"));
            row.put("questionId", rs.getLong("question_id"));
            row.put("questionText", rs.getString("question_text"));
            row.put("language", rs.getString("language"));
            row.put("sourceCode", rs.getString("source_code"));
            row.put("testsPassed", rs.getObject("tests_passed"));
            row.put("testsTotal", rs.getObject("tests_total"));
            row.put("awardedMarks", rs.getObject("awarded_marks"));
            row.put("judgeMessage", rs.getString("judge_message"));
            row.put("submittedAt", rs.getTimestamp("updated_at"));
            return row;
        }, examId, questionId, questionId);
    }
}
