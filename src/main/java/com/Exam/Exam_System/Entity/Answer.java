package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One candidate's response to one question.
 *
 * The unique constraint is load-bearing, not just hygiene: it lets the hot save
 * path be a single atomic upsert (INSERT ... ON DUPLICATE KEY UPDATE) instead of
 * a read-then-write, and it makes a duplicate response physically impossible
 * even under concurrent retries.
 */
@Entity
@Table(
    name = "answers",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_answer_attempt_question",
        columnNames = {"attempt_id", "question_id"}
    )
)
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "selected_option")
    private String selectedOption;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    // ── Coding answers ───────────────────────────────────────────────────
    //
    // An MCQ answer is one letter. A coding answer is a program, the language
    // it is written in, and how it fared against the test cases — which is
    // also what makes partial marks possible, because a candidate whose
    // solution passes seven cases of ten has not simply got it wrong.

    @Column(columnDefinition = "TEXT")
    private String sourceCode;

    private String language;

    private Integer testsPassed;

    private Integer testsTotal;

    /** What this answer actually earned. Null on an MCQ, which is derived. */
    private Double awardedMarks;

    /** Compiler output, kept so an invigilator can answer "why did it score 0". */
    @Column(columnDefinition = "TEXT")
    private String judgeMessage;

    /**
     * When the candidate last changed this response. Doubles as an audit trail
     * for disputes ("when did they answer Q41?") and guarantees the upsert
     * always reports a row as affected, even when the same option is re-picked.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Answer() {}

    public Long getId() { return id; }

    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getSelectedOption() { return selectedOption; }
    public void setSelectedOption(String selectedOption) { this.selectedOption = selectedOption; }

    public Boolean getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Boolean isCorrect) { this.isCorrect = isCorrect; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Integer getTestsPassed() { return testsPassed; }
    public void setTestsPassed(Integer testsPassed) { this.testsPassed = testsPassed; }

    public Integer getTestsTotal() { return testsTotal; }
    public void setTestsTotal(Integer testsTotal) { this.testsTotal = testsTotal; }

    public Double getAwardedMarks() { return awardedMarks; }
    public void setAwardedMarks(Double awardedMarks) { this.awardedMarks = awardedMarks; }

    public String getJudgeMessage() { return judgeMessage; }
    public void setJudgeMessage(String judgeMessage) { this.judgeMessage = judgeMessage; }
}
