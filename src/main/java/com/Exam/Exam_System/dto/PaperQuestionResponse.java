package com.Exam.Exam_System.dto;

import java.util.List;

/**
 * A question as shown to a candidate during the exam.
 *
 * Deliberately carries no correct-answer field. The previous QuestionResponse
 * exposed `correctIndex` over /student/exam/{examId}, which put the full answer
 * key one DevTools tab away from every candidate.
 */
public class PaperQuestionResponse {

    /** One option as displayed. `id` is the canonical letter and is what the client sends back. */
    public static class OptionView {
        private final String id;
        private final String text;
        private final String image;

        public OptionView(String id, String text, String image) {
            this.id = id;
            this.text = text;
            this.image = image;
        }

        public String getId() { return id; }
        public String getText() { return text; }
        public String getImage() { return image; }
    }

    private final Long id;
    private final int displayNumber;
    private final Long sectionId;
    private final String sectionName;
    private final String questionText;
    private final String questionImage;
    private final Integer marks;
    private final Double negativeMarks;
    private final List<OptionView> options;

    // ── Coding questions ─────────────────────────────────────────────────
    //
    // Null on an MCQ. What is here is everything a candidate is entitled to
    // see: the statement, the limits, and the worked example. What is NOT here
    // is the hidden test cases — they never travel to a browser, which is why
    // this DTO is assembled field by field rather than serialising the
    // question entity that now holds both.

    private final String type;
    private final String constraintsText;
    private final String sampleInput;
    private final String sampleOutput;
    private final String sampleExplanation;
    private final String starterCode;
    private final List<java.util.Map<String, String>> languages;

    public PaperQuestionResponse(Long id, int displayNumber, Long sectionId, String sectionName,
                                 String questionText, String questionImage,
                                 Integer marks, Double negativeMarks, List<OptionView> options) {
        this(id, displayNumber, sectionId, sectionName, questionText, questionImage,
             marks, negativeMarks, options, "MCQ", null, null, null, null, null, null);
    }

    public PaperQuestionResponse(Long id, int displayNumber, Long sectionId, String sectionName,
                                 String questionText, String questionImage,
                                 Integer marks, Double negativeMarks, List<OptionView> options,
                                 String type, String constraintsText, String sampleInput,
                                 String sampleOutput, String sampleExplanation, String starterCode,
                                 List<java.util.Map<String, String>> languages) {
        this.id = id;
        this.displayNumber = displayNumber;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.questionText = questionText;
        this.questionImage = questionImage;
        this.marks = marks;
        this.negativeMarks = negativeMarks;
        this.options = options;
        this.type = type;
        this.constraintsText = constraintsText;
        this.sampleInput = sampleInput;
        this.sampleOutput = sampleOutput;
        this.sampleExplanation = sampleExplanation;
        this.starterCode = starterCode;
        this.languages = languages;
    }

    public String getType() { return type; }
    public String getConstraintsText() { return constraintsText; }
    public String getSampleInput() { return sampleInput; }
    public String getSampleOutput() { return sampleOutput; }
    public String getSampleExplanation() { return sampleExplanation; }
    public String getStarterCode() { return starterCode; }
    public List<java.util.Map<String, String>> getLanguages() { return languages; }

    public Long getId() { return id; }
    public int getDisplayNumber() { return displayNumber; }
    public Long getSectionId() { return sectionId; }
    public String getSectionName() { return sectionName; }
    public String getQuestionText() { return questionText; }
    public String getQuestionImage() { return questionImage; }
    public Integer getMarks() { return marks; }
    public Double getNegativeMarks() { return negativeMarks; }
    public List<OptionView> getOptions() { return options; }
}
