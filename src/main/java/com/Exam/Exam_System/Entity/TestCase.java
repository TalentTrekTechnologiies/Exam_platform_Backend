package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

/**
 * One input/expected-output pair a coding answer is judged against.
 *
 * Two kinds, and the distinction is the whole point of a coding round:
 *
 *   · A SAMPLE case is shown to the candidate, and is what "Compile & Run"
 *     executes against. It tells them their program reads and writes in the
 *     shape the problem asked for.
 *
 *   · A HIDDEN case is never shown. It is what the marks are actually made of,
 *     and it is why a program that special-cases the sample earns nothing.
 *
 * Marks are carried per case rather than per question so a setter can weight
 * the edge cases — an empty array or the maximum n — above the easy ones, the
 * way a real paper does.
 */
@Entity
@Table(
    name = "test_cases",
    indexes = @Index(name = "idx_test_case_question", columnList = "questionId")
)
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long questionId;

    @Column(columnDefinition = "TEXT")
    private String input;

    @Column(columnDefinition = "TEXT")
    private String expectedOutput;

    /**
     * Whether the candidate may see this case.
     *
     * Defaults to hidden. Getting this the wrong way round would hand every
     * candidate the answer key, so the safe value is the one you get by
     * forgetting to choose.
     */
    @Column(nullable = false)
    private boolean sample = false;

    /** Relative weight within the question. Equal weights are the normal case. */
    private Double weight = 1.0;

    /** Shown beside a failed sample run, e.g. "empty input". Never for hidden cases. */
    private String label;

    private Integer displayOrder;

    public TestCase() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public boolean isSample() { return sample; }
    public void setSample(boolean sample) { this.sample = sample; }

    public Double getWeight() { return weight == null || weight <= 0 ? 1.0 : weight; }
    public void setWeight(Double weight) { this.weight = weight; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
