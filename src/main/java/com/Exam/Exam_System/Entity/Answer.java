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
}
