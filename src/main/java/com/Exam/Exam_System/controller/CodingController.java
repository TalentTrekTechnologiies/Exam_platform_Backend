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
import com.Exam.Exam_System.service.judge.Language;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public CodingController(QuestionRepository questions,
                            TestCaseRepository testCases,
                            AnswerRepository answers,
                            AccessGuard accessGuard,
                            CodeExecutionService execution) {
        this.questions = questions;
        this.testCases = testCases;
        this.answers = answers;
        this.accessGuard = accessGuard;
        this.execution = execution;
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

        CodeExecutionService.Judgement judgement = execution.judge(question, language, source);

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
}
