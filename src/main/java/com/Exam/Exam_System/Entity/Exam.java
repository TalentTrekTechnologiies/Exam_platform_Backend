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
}