package com.Exam.Exam_System.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * One question as understood from an uploaded document, before anyone has
 * agreed it is right.
 *
 * This is deliberately a *proposal*, not a question. Document parsing is
 * inherently lossy — papers are laid out for human eyes, not machines — so
 * everything here carries its confidence and its problems, and nothing reaches
 * the question bank until a person has looked at it.
 */
public class ParsedQuestion {

    private int sourceNumber;
    private String questionText;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctAnswer;
    private Integer marks;
    private Double negativeMarks;
    private String sectionName;

    /** Filenames of images found near this question, if any. */
    private List<String> images = new ArrayList<>();

    /**
     * Why this row may need a human eye: a missing option, no answer key found,
     * text that ran together. Empty means the parse looked clean — which is
     * still not the same as correct.
     */
    private List<String> issues = new ArrayList<>();

    /** False when something essential is missing; such a row cannot be imported. */
    public boolean isUsable() {
        return questionText != null && !questionText.isBlank()
                && optionA != null && !optionA.isBlank()
                && optionB != null && !optionB.isBlank();
    }

    public void addIssue(String issue) { issues.add(issue); }

    public int getSourceNumber() { return sourceNumber; }
    public void setSourceNumber(int sourceNumber) { this.sourceNumber = sourceNumber; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getOptionA() { return optionA; }
    public void setOptionA(String optionA) { this.optionA = optionA; }

    public String getOptionB() { return optionB; }
    public void setOptionB(String optionB) { this.optionB = optionB; }

    public String getOptionC() { return optionC; }
    public void setOptionC(String optionC) { this.optionC = optionC; }

    public String getOptionD() { return optionD; }
    public void setOptionD(String optionD) { this.optionD = optionD; }

    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }

    public Integer getMarks() { return marks; }
    public void setMarks(Integer marks) { this.marks = marks; }

    public Double getNegativeMarks() { return negativeMarks; }
    public void setNegativeMarks(Double negativeMarks) { this.negativeMarks = negativeMarks; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public List<String> getIssues() { return issues; }
    public void setIssues(List<String> issues) { this.issues = issues; }
}
