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
}