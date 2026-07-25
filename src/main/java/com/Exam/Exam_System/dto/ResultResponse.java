package com.Exam.Exam_System.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full scorecard for a submitted attempt.
 *
 * Shape matches what Result.jsx actually reads. The old endpoint returned only
 * {attemptId, score, status, startTime, endTime}, so every stat on the result
 * page rendered as NaN.
 */
public class ResultResponse {

    /** Per-section breakdown — the sectional scorecard EAMCET/NQT candidates expect. */
    public static class SectionScore {
        private final Long sectionId;
        private final String sectionName;
        private final int correct;
        private final int incorrect;
        private final int unanswered;
        private final double score;
        private final double maxScore;

        /**
         * What everyone else averaged in this section.
         *
         * A raw section score says little on its own — 12/20 in Mathematics
         * means one thing if the cohort averaged 8 and quite another if they
         * averaged 17. This is what turns a scorecard into something a student
         * can actually revise from. Null until anyone else has submitted.
         */
        private Double cohortAverage;

        public SectionScore(Long sectionId, String sectionName, int correct, int incorrect,
                            int unanswered, double score, double maxScore) {
            this.sectionId = sectionId;
            this.sectionName = sectionName;
            this.correct = correct;
            this.incorrect = incorrect;
            this.unanswered = unanswered;
            this.score = score;
            this.maxScore = maxScore;
        }

        public Double getCohortAverage() { return cohortAverage; }
        public void setCohortAverage(Double cohortAverage) { this.cohortAverage = cohortAverage; }

        public Long getSectionId() { return sectionId; }
        public String getSectionName() { return sectionName; }
        public int getCorrect() { return correct; }
        public int getIncorrect() { return incorrect; }
        public int getUnanswered() { return unanswered; }
        public double getScore() { return score; }
        public double getMaxScore() { return maxScore; }
    }

    /** One row of the response sheet. Only ever built for a SUBMITTED attempt. */
    public static class ReviewQuestion {
        private final Long id;
        private final int displayNumber;
        private final String sectionName;
        private final String questionText;
        private final String questionImage;
        private final List<PaperQuestionResponse.OptionView> options;
        private final String correctAnswer;
        private final String yourAnswer;
        private final boolean correct;
        private final boolean attempted;
        private final double awarded;

        public ReviewQuestion(Long id, int displayNumber, String sectionName, String questionText,
                              String questionImage, List<PaperQuestionResponse.OptionView> options,
                              String correctAnswer, String yourAnswer, boolean correct,
                              boolean attempted, double awarded) {
            this.id = id;
            this.displayNumber = displayNumber;
            this.sectionName = sectionName;
            this.questionText = questionText;
            this.questionImage = questionImage;
            this.options = options;
            this.correctAnswer = correctAnswer;
            this.yourAnswer = yourAnswer;
            this.correct = correct;
            this.attempted = attempted;
            this.awarded = awarded;
        }

        public Long getId() { return id; }
        public int getDisplayNumber() { return displayNumber; }
        public String getSectionName() { return sectionName; }
        public String getQuestionText() { return questionText; }
        public String getQuestionImage() { return questionImage; }
        public List<PaperQuestionResponse.OptionView> getOptions() { return options; }
        public String getCorrectAnswer() { return correctAnswer; }
        public String getYourAnswer() { return yourAnswer; }
        public boolean isCorrect() { return correct; }
        public boolean isAttempted() { return attempted; }
        public double getAwarded() { return awarded; }
    }

    private Long attemptId;
    private String status;
    private String studentName;
    private String hallTicket;
    private String examTitle;
    private double score;
    private double maxScore;
    private double percentage;
    private int correct;
    private int incorrect;
    private int unanswered;
    private int total;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long timeTakenSeconds;
    private List<SectionScore> sections;
    private List<ReviewQuestion> questions;

    // ── Standing in the cohort ───────────────────────────────────────────────
    // A mock exam's whole purpose is telling a candidate where they stand while
    // there is still time to act on it. A bare score cannot do that.

    /** Competition rank: equal scores share a rank, and the next rank skips. */
    private Integer rank;

    /** How many candidates have submitted and are therefore ranked. */
    private int totalRanked;

    /** Percentage of ranked candidates scoring strictly below this one. */
    private Double percentile;

    private Double topScore;
    private Double cohortAverage;

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public int getTotalRanked() { return totalRanked; }
    public void setTotalRanked(int totalRanked) { this.totalRanked = totalRanked; }

    public Double getPercentile() { return percentile; }
    public void setPercentile(Double percentile) { this.percentile = percentile; }

    public Double getTopScore() { return topScore; }
    public void setTopScore(Double topScore) { this.topScore = topScore; }

    public Double getCohortAverage() { return cohortAverage; }
    public void setCohortAverage(Double cohortAverage) { this.cohortAverage = cohortAverage; }

    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getHallTicket() { return hallTicket; }
    public void setHallTicket(String hallTicket) { this.hallTicket = hallTicket; }

    public String getExamTitle() { return examTitle; }
    public void setExamTitle(String examTitle) { this.examTitle = examTitle; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }

    public double getPercentage() { return percentage; }
    public void setPercentage(double percentage) { this.percentage = percentage; }

    public int getCorrect() { return correct; }
    public void setCorrect(int correct) { this.correct = correct; }

    public int getIncorrect() { return incorrect; }
    public void setIncorrect(int incorrect) { this.incorrect = incorrect; }

    public int getUnanswered() { return unanswered; }
    public void setUnanswered(int unanswered) { this.unanswered = unanswered; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public long getTimeTakenSeconds() { return timeTakenSeconds; }
    public void setTimeTakenSeconds(long timeTakenSeconds) { this.timeTakenSeconds = timeTakenSeconds; }

    public List<SectionScore> getSections() { return sections; }
    public void setSections(List<SectionScore> sections) { this.sections = sections; }

    public List<ReviewQuestion> getQuestions() { return questions; }
    public void setQuestions(List<ReviewQuestion> questions) { this.questions = questions; }
}
