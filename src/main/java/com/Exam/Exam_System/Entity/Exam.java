package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exams")
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning institution — the tenant key that every admin query filters on.
     * `collegeName` below is display text only and must never decide access.
     */
    @Column(name = "admin_id")
    private Long adminId;

    private String collegeName;

    private String collegeLogo;

    private String title;

    private Integer duration;

    private LocalDateTime startDate;

    private LocalDateTime endDate;
    private String introVideo;
    private boolean enableCamera;
    private boolean enableMic;

    /**
     * Whether candidates may sit this exam.
     *
     * Defaults to false so a half-built paper can never be walked into by
     * accident: an exam with three questions written so far, or an unchecked
     * answer key, stays closed until someone deliberately publishes it. Slots
     * control *when* an exam is open; this controls *whether* it is ready at all.
     */
    @Column(name = "published", nullable = false)
    private boolean published = false;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * This exam's default marking scheme, applied to any question that doesn't
     * declare its own.
     *
     * These were previously collected on the Create Exam screen and then thrown
     * away: the server had nowhere to put them, so they survived only in one
     * browser's local storage. An admin who set "4 marks, −1 negative" and then
     * imported a CSV or PDF got questions worth 1 mark with no penalty, with
     * nothing to indicate their scheme had been discarded — a silent, and
     * therefore expensive, mismatch between what was configured and what
     * candidates were actually marked against.
     *
     * Nullable so an exam created before this existed keeps behaving exactly as
     * it did; ScoringService's own fallbacks (1 mark, no penalty) still apply
     * when both the question and the exam are silent.
     */
    @Column(name = "default_marks")
    private Integer defaultMarks;

    @Column(name = "default_negative_marks")
    private Double defaultNegativeMarks;


    /**
     * The sittings requested when this exam was created.
     *
     * Request-only, and @Transient so JPA never tries to store it: slots are
     * rows of their own. It exists so an exam and its sittings arrive together
     * — a college running a morning and an evening batch should say so once,
     * rather than create the exam and then go hunting for a separate screen.
     */
    @Transient
    private java.util.List<SlotWindow> slots;

    /** One requested sitting. */
    public static class SlotWindow {
        private LocalDateTime startTime;
        private LocalDateTime endTime;

        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime v) { this.startTime = v; }

        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime v) { this.endTime = v; }
    }

    public java.util.List<SlotWindow> getSlots() { return slots; }
    public void setSlots(java.util.List<SlotWindow> slots) { this.slots = slots; }

    public Exam() {}

    public Long getId() {
        return id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getCollegeName() {
        return collegeName;
    }

    public void setCollegeName(String collegeName) {
        this.collegeName = collegeName;
    }

    public String getCollegeLogo() {
        return collegeLogo;
    }

    public void setCollegeLogo(String collegeLogo) {
        this.collegeLogo = collegeLogo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public LocalDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDateTime startDate) {
        this.startDate = startDate;
    }

    public LocalDateTime getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDateTime endDate) {
        this.endDate = endDate;
    }

	public String getIntroVideo() {
		return introVideo;
	}

	public void setIntroVideo(String introVideo) {
		this.introVideo = introVideo;
	}
	public boolean isEnableCamera() { return enableCamera; }
	public void setEnableCamera(boolean enableCamera) { this.enableCamera = enableCamera; }

	public boolean isEnableMic() { return enableMic; }
	public void setEnableMic(boolean enableMic) { this.enableMic = enableMic; }

	public boolean isPublished() { return published; }
	public void setPublished(boolean published) { this.published = published; }

	public LocalDateTime getPublishedAt() { return publishedAt; }
	public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

	public Integer getDefaultMarks() { return defaultMarks; }
	public void setDefaultMarks(Integer defaultMarks) { this.defaultMarks = defaultMarks; }

	public Double getDefaultNegativeMarks() { return defaultNegativeMarks; }
	public void setDefaultNegativeMarks(Double defaultNegativeMarks) { this.defaultNegativeMarks = defaultNegativeMarks; }
}