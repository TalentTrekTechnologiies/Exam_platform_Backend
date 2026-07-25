package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.*;
import com.Exam.Exam_System.dto.ResultResponse;
import com.Exam.Exam_System.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The marking maths.
 *
 * A wrong score is the worst failure this system can produce — worse than an
 * outage, because it is silent and a candidate acts on it. These tests pin the
 * three schemes the platform claims to support (EAMCET, NEET/JEE, NQT) and the
 * edge cases around them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoringServiceTest {

    @Mock AttemptRepository attemptRepository;
    @Mock AnswerRepository answerRepository;
    @Mock QuestionRepository questionRepository;
    @Mock AttemptQuestionRepository attemptQuestionRepository;
    @Mock StudentRepository studentRepository;
    @Mock ExamRepository examRepository;
    @Mock PaperService paperService;
    @Mock AttemptCache attemptCache;
    @Mock RankingService rankingService;

    ScoringService scoring;

    private static final long ATTEMPT = 500L;

    @BeforeEach
    void setUp() {
        // Ranking is mocked: these tests pin the marking maths, and a candidate's
        // standing against a cohort is a separate concern with its own tests.
        scoring = new ScoringService(attemptRepository, answerRepository, questionRepository,
                attemptQuestionRepository, studentRepository, examRepository, paperService,
                attemptCache, rankingService);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Question question(long id, int marks, double negative) {
        Question q = new Question();
        q.setExamId(1L);
        q.setSectionId(10L);
        q.setQuestionText("Q" + id);
        q.setOptionA("a"); q.setOptionB("b"); q.setOptionC("c"); q.setOptionD("d");
        q.setCorrectAnswer("A");
        q.setMarks(marks);
        q.setNegativeMarks(negative);
        try {
            var f = Question.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(q, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return q;
    }

    private Answer answer(long questionId, String option) {
        Answer a = new Answer();
        a.setAttemptId(ATTEMPT);
        a.setQuestionId(questionId);
        a.setSelectedOption(option);
        return a;
    }

    /** Wires a live attempt with the given questions and responses. */
    private void givenPaper(List<Question> questions, List<Answer> answers, String status) {
        Attempt attempt = new Attempt();
        attempt.setStudentId(3L);
        attempt.setExamId(1L);
        attempt.setStatus(status);
        attempt.setStartTime(LocalDateTime.now().minusMinutes(30));
        attempt.setEndTime(LocalDateTime.now().plusMinutes(150));
        try {
            var f = Attempt.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(attempt, ATTEMPT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        List<AttemptQuestion> layout = new ArrayList<>();
        int order = 1;
        for (Question q : questions) layout.add(new AttemptQuestion(ATTEMPT, q.getId(), order++, "A,B,C,D"));

        when(attemptRepository.findById(ATTEMPT)).thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any(Attempt.class))).thenAnswer(i -> i.getArgument(0));
        when(attemptQuestionRepository.findByAttemptIdOrderByDisplayOrderAsc(ATTEMPT)).thenReturn(layout);
        when(questionRepository.findAllById(anyIterable())).thenReturn(questions);
        when(answerRepository.findByAttemptId(ATTEMPT)).thenReturn(answers);
        when(paperService.sectionNamesFor(anyCollection())).thenReturn(Map.of(10L, "Physics"));
        when(paperService.optionsInDisplayOrder(any(), anyString())).thenReturn(List.of());
    }

    // ── the three marking schemes ───────────────────────────────────────────

    @Test
    @DisplayName("NEET/JEE: +4 correct, -1 wrong, 0 unanswered")
    void neetScheme() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0),
                                    question(3, 4, 1.0), question(4, 4, 1.0));
        // right, right, wrong, unanswered  ->  4 + 4 - 1 + 0 = 7
        givenPaper(qs, List.of(answer(1, "A"), answer(2, "A"), answer(3, "B")), "STARTED");

        Attempt graded = scoring.submit(ATTEMPT, "test");
        assertEquals(7.0, graded.getScore(), 0.001);
    }

    @Test
    @DisplayName("EAMCET: +1 correct and NO penalty for a wrong answer")
    void eamcetScheme() {
        List<Question> qs = List.of(question(1, 1, 0.0), question(2, 1, 0.0), question(3, 1, 0.0));
        givenPaper(qs, List.of(answer(1, "A"), answer(2, "C"), answer(3, "D")), "STARTED");

        // One right, two wrong, no negative marking -> exactly 1.
        assertEquals(1.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    @Test
    @DisplayName("a paper may mix marking schemes question by question")
    void mixedScheme() {
        // 4-mark with penalty, 2-mark without, 1-mark with half penalty.
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 2, 0.0), question(3, 1, 0.5));
        givenPaper(qs, List.of(answer(1, "A"), answer(2, "B"), answer(3, "C")), "STARTED");

        // right(+4), wrong(0), wrong(-0.5) = 3.5
        assertEquals(3.5, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    // ── edge cases that decide real results ─────────────────────────────────

    @Test
    @DisplayName("an unanswered question is never penalised")
    void unansweredNeverPenalised() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0));
        givenPaper(qs, List.of(), "STARTED");
        assertEquals(0.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    @Test
    @DisplayName("a blank response counts as unanswered, not wrong")
    void blankIsNotWrong() {
        List<Question> qs = List.of(question(1, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "   ")), "STARTED");
        assertEquals(0.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001,
                "whitespace must not be graded as an incorrect attempt");
    }

    @Test
    @DisplayName("answer matching ignores case")
    void caseInsensitive() {
        List<Question> qs = List.of(question(1, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "a")), "STARTED");
        assertEquals(4.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    @Test
    @DisplayName("negative marking can drive a score below zero — it is not clamped")
    void scoreCanGoNegative() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "B"), answer(2, "C")), "STARTED");
        assertEquals(-2.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    @Test
    @DisplayName("a negative marks value stored as negative is still treated as a penalty")
    void negativeStoredNegative() {
        // -1.0 and 1.0 must both mean "deduct one mark", never "add one".
        List<Question> qs = List.of(question(1, 4, -1.0));
        givenPaper(qs, List.of(answer(1, "D")), "STARTED");
        assertEquals(-1.0, scoring.submit(ATTEMPT, "test").getScore(), 0.001);
    }

    @Test
    @DisplayName("submitting twice grades once and keeps the original score")
    void submitIsIdempotent() {
        List<Question> qs = List.of(question(1, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "A")), "SUBMITTED");

        Attempt again = scoring.submit(ATTEMPT, "duplicate");
        // Already submitted: it returns immediately without re-grading or saving.
        verify(attemptRepository, never()).save(any());
        assertEquals("SUBMITTED", again.getStatus());
    }

    @Test
    @DisplayName("grading marks each answer correct or incorrect for the response sheet")
    void persistsCorrectness() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "A"), answer(2, "B")), "STARTED");

        scoring.submit(ATTEMPT, "test");

        ArgumentCaptor<List<Answer>> saved = ArgumentCaptor.forClass(List.class);
        verify(answerRepository).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size());
        assertTrue(saved.getValue().get(0).getIsCorrect());
        assertFalse(saved.getValue().get(1).getIsCorrect());
    }

    // ── the scorecard ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the result refuses to reveal anything while the exam is still live")
    void resultWithheldUntilSubmitted() {
        givenPaper(List.of(question(1, 4, 1.0)), List.of(), "STARTED");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> scoring.buildResult(ATTEMPT));
        assertEquals("RESULT_NOT_READY", e.getMessage(),
                "the review section contains the answer key");
    }

    @Test
    @DisplayName("the scorecard totals reconcile with the paper")
    void scorecardReconciles() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0),
                                    question(3, 4, 1.0), question(4, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "A"), answer(2, "A"), answer(3, "B")), "SUBMITTED");

        ResultResponse r = scoring.buildResult(ATTEMPT);

        assertEquals(2, r.getCorrect());
        assertEquals(1, r.getIncorrect());
        assertEquals(1, r.getUnanswered());
        assertEquals(4, r.getTotal(), "every question lands in exactly one bucket");
        assertEquals(r.getCorrect() + r.getIncorrect() + r.getUnanswered(), r.getTotal());
        assertEquals(16.0, r.getMaxScore(), 0.001);
        assertEquals(7.0, r.getScore(), 0.001);
        assertEquals(43.75, r.getPercentage(), 0.01);
    }

    @Test
    @DisplayName("percentage never reports negative even when the score is")
    void percentageFloorsAtZero() {
        List<Question> qs = List.of(question(1, 4, 1.0), question(2, 4, 1.0));
        givenPaper(qs, List.of(answer(1, "B"), answer(2, "C")), "SUBMITTED");

        ResultResponse r = scoring.buildResult(ATTEMPT);
        assertTrue(r.getScore() < 0, "the raw score is genuinely negative");
        assertEquals(0.0, r.getPercentage(), 0.001, "but a percentage below zero is meaningless");
    }
}
