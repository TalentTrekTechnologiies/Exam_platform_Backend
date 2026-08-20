package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long examId;
    private Long sectionId;

    /**
     * What kind of question this is: MCQ or CODING.
     *
     * Defaults to MCQ so every question written before coding existed keeps
     * behaving exactly as it did — the column is added, and nothing that reads
     * it needs to know a second kind ever appeared.
     */
    public static final String MCQ = "MCQ";
    public static final String CODING = "CODING";

    @Column(nullable = false)
    private String type = MCQ;

    /**
     * TEXT, not the default VARCHAR(255).
     *
     * A coding problem statement is several paragraphs, and 255 characters
     * silently truncates it. The same limit was already a hazard for a long
     * comprehension or data-interpretation question.
     */
    @Column(columnDefinition = "TEXT")
    private String questionText;

    private String questionImage;

    private String optionA;
    private String optionAImage;

    private String optionB;
    private String optionBImage;

    private String optionC;
    private String optionCImage;

    private String optionD;
    private String optionDImage;

    private String correctAnswer;

    private Integer marks;

    private Double negativeMarks;

    // ── Coding questions only ────────────────────────────────────────────
    //
    // Null on an MCQ. Kept on the question rather than in a side table because
    // a coding question IS a question — it sits in a section, carries marks,
    // and appears on the paper alongside the aptitude round exactly as TCS NQT
    // presents it.

    /** Input format, output format, limits on n — shown under the statement. */
    @Column(columnDefinition = "TEXT")
    private String constraintsText;

    /** The worked example every candidate is given before they start typing. */
    @Column(columnDefinition = "TEXT")
    private String sampleInput;

    @Column(columnDefinition = "TEXT")
    private String sampleOutput;

    /** Why that output follows from that input. Optional, but it saves questions. */
    @Column(columnDefinition = "TEXT")
    private String sampleExplanation;

    /**
     * Which languages this problem may be answered in, comma separated.
     *
     * Per question, because a problem about pointers is not the same problem in
     * Python. Null means the exam's whole list is allowed.
     */
    private String allowedLanguages;

    /** Per test case, not for the whole submission. */
    private Integer timeLimitMs;

    private Integer memoryLimitMb;

    /** Optional skeleton placed in the editor, e.g. a required function signature. */
    @Column(columnDefinition = "TEXT")
    private String starterCode;

    public Question() {}

    public Long getId() {
        return id;
    }

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getQuestionImage() {
        return questionImage;
    }

    public void setQuestionImage(String questionImage) {
        this.questionImage = questionImage;
    }

    public String getOptionA() {
        return optionA;
    }

    public void setOptionA(String optionA) {
        this.optionA = optionA;
    }

    public String getOptionAImage() {
        return optionAImage;
    }

    public void setOptionAImage(String optionAImage) {
        this.optionAImage = optionAImage;
    }

    public String getOptionB() {
        return optionB;
    }

    public void setOptionB(String optionB) {
        this.optionB = optionB;
    }

    public String getOptionBImage() {
        return optionBImage;
    }

    public void setOptionBImage(String optionBImage) {
        this.optionBImage = optionBImage;
    }

    public String getOptionC() {
        return optionC;
    }

    public void setOptionC(String optionC) {
        this.optionC = optionC;
    }

    public String getOptionCImage() {
        return optionCImage;
    }

    public void setOptionCImage(String optionCImage) {
        this.optionCImage = optionCImage;
    }

    public String getOptionD() {
        return optionD;
    }

    public void setOptionD(String optionD) {
        this.optionD = optionD;
    }

    public String getOptionDImage() {
        return optionDImage;
    }

    public void setOptionDImage(String optionDImage) {
        this.optionDImage = optionDImage;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public Integer getMarks() {
        return marks;
    }

    public void setMarks(Integer marks) {
        this.marks = marks;
    }

    public Double getNegativeMarks() {
        return negativeMarks;
    }

    public void setNegativeMarks(Double negativeMarks) {
        this.negativeMarks = negativeMarks;
    }
    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public String getType() { return type == null ? MCQ : type; }
    public void setType(String type) { this.type = type; }

    /** True when this question is answered with code rather than a letter. */
    public boolean isCoding() { return CODING.equalsIgnoreCase(getType()); }

    public String getConstraintsText() { return constraintsText; }
    public void setConstraintsText(String constraintsText) { this.constraintsText = constraintsText; }

    public String getSampleInput() { return sampleInput; }
    public void setSampleInput(String sampleInput) { this.sampleInput = sampleInput; }

    public String getSampleOutput() { return sampleOutput; }
    public void setSampleOutput(String sampleOutput) { this.sampleOutput = sampleOutput; }

    public String getSampleExplanation() { return sampleExplanation; }
    public void setSampleExplanation(String sampleExplanation) { this.sampleExplanation = sampleExplanation; }

    public String getAllowedLanguages() { return allowedLanguages; }
    public void setAllowedLanguages(String allowedLanguages) { this.allowedLanguages = allowedLanguages; }

    public Integer getTimeLimitMs() { return timeLimitMs; }
    public void setTimeLimitMs(Integer timeLimitMs) { this.timeLimitMs = timeLimitMs; }

    public Integer getMemoryLimitMb() { return memoryLimitMb; }
    public void setMemoryLimitMb(Integer memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }

    public String getStarterCode() { return starterCode; }
    public void setStarterCode(String starterCode) { this.starterCode = starterCode; }
}
