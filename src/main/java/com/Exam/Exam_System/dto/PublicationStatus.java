package com.Exam.Exam_System.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether an exam is fit to be sat, and the link candidates use to sit it.
 *
 * The blocking/warning split is deliberate. A blocker is something that would
 * make the sitting fail outright — no questions, no slot, nobody enrolled.
 * A warning is something a competent exam officer might well intend, such as
 * publishing before the roll list is complete, so it informs rather than
 * obstructs.
 */
public class PublicationStatus {

    private boolean published;
    private LocalDateTime publishedAt;

    /** The address candidates open. Null until an institution code is known. */
    private String candidateLink;

    private int questionCount;
    private int sectionCount;
    private int candidateCount;
    private int slotCount;
    private int preparedPapers;
    private double totalMarks;

    private List<String> blockers;
    private List<String> warnings;

    public boolean isReady() { return blockers == null || blockers.isEmpty(); }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public String getCandidateLink() { return candidateLink; }
    public void setCandidateLink(String candidateLink) { this.candidateLink = candidateLink; }

    public int getQuestionCount() { return questionCount; }
    public void setQuestionCount(int questionCount) { this.questionCount = questionCount; }

    public int getSectionCount() { return sectionCount; }
    public void setSectionCount(int sectionCount) { this.sectionCount = sectionCount; }

    public int getCandidateCount() { return candidateCount; }
    public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }

    public int getSlotCount() { return slotCount; }
    public void setSlotCount(int slotCount) { this.slotCount = slotCount; }

    public int getPreparedPapers() { return preparedPapers; }
    public void setPreparedPapers(int preparedPapers) { this.preparedPapers = preparedPapers; }

    public double getTotalMarks() { return totalMarks; }
    public void setTotalMarks(double totalMarks) { this.totalMarks = totalMarks; }

    public List<String> getBlockers() { return blockers; }
    public void setBlockers(List<String> blockers) { this.blockers = blockers; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
