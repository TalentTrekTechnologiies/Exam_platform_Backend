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

    public PaperQuestionResponse(Long id, int displayNumber, Long sectionId, String sectionName,
                                 String questionText, String questionImage,
                                 Integer marks, Double negativeMarks, List<OptionView> options) {
        this.id = id;
        this.displayNumber = displayNumber;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.questionText = questionText;
        this.questionImage = questionImage;
        this.marks = marks;
        this.negativeMarks = negativeMarks;
        this.options = options;
    }

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
