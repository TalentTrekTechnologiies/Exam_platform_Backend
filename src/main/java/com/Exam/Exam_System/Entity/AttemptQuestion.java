package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

/**
 * The frozen paper layout for a single attempt.
 *
 * Question order and option order are decided once, when the attempt starts, and
 * stored here. Every later fetch replays this exact layout, so refreshing the
 * page mid-exam no longer reshuffles the paper under the candidate.
 */
@Entity
@Table(
    name = "attempt_questions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_attempt_question",
        columnNames = {"attempt_id", "question_id"}
    ),
    indexes = @Index(name = "idx_attempt_order", columnList = "attempt_id, display_order")
)
public class AttemptQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 1-based position of this question in the candidate's paper. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /**
     * Canonical option letters in the order this candidate sees them, e.g. "C,A,D,B".
     * Answers are always stored as the canonical letter, so this is presentation only.
     */
    @Column(name = "option_order", nullable = false, length = 32)
    private String optionOrder;

    public AttemptQuestion() {}

    public AttemptQuestion(Long attemptId, Long questionId, Integer displayOrder, String optionOrder) {
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.displayOrder = displayOrder;
        this.optionOrder = optionOrder;
    }

    public Long getId() { return id; }

    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public String getOptionOrder() { return optionOrder; }
    public void setOptionOrder(String optionOrder) { this.optionOrder = optionOrder; }
}
