package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sections")
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long examId;

    private String name;

    // ✅ NEW FIELD
    private Integer totalMarks;

    /**
     * How many of this section's questions each candidate actually sits.
     *
     * Null, or a number at or above the bank, means everyone gets all of them —
     * which is what every existing exam does and keeps doing.
     *
     * Set it lower and the section becomes a pool: a college imports two
     * hundred Physics questions and each candidate draws twenty of them. The
     * draw is per candidate and frozen with the rest of the paper, so it
     * survives a refresh, and two people sitting side by side are not looking
     * at the same paper at all — worth considerably more than shuffling the
     * order of a set everybody shares.
     *
     * Held per section rather than per exam on purpose. "Any sixty of two
     * hundred" across a sectioned paper draws whatever the shuffle happens to
     * give: one candidate sits forty-one Physics and six Maths, the next a
     * different shape entirely, and neither paper is the exam the college
     * meant to set.
     */
    @Column(name = "questions_to_draw")
    private Integer questionsToDraw;

    /**
     * Minutes a candidate gets for this section alone, or null for a paper with
     * one clock for the whole thing.
     *
     * Setting it on every section of an exam turns it into a sectional paper:
     * the sections are sat in order, each with its own countdown, and a section
     * whose time is up is closed and cannot be returned to. That lock is the
     * point — it is what TCS NQT actually is, and a "sectional" exam a
     * candidate can drift back into is not one.
     *
     * Left null everywhere, nothing changes: one clock, free navigation, which
     * is what EAMCET and NEET want and what every existing exam does.
     */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    public Section() {}

    public Long getId() {
        return id;
    }

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // ✅ NEW GETTER/SETTER
    public Integer getTotalMarks() {
        return totalMarks;
    }

    public void setTotalMarks(Integer totalMarks) {
        this.totalMarks = totalMarks;
    }

    public Integer getQuestionsToDraw() {
        return questionsToDraw;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public void setQuestionsToDraw(Integer questionsToDraw) {
        this.questionsToDraw = questionsToDraw;
    }
}