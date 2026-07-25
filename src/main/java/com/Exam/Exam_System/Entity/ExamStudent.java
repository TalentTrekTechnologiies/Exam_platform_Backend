package com.Exam.Exam_System.Entity;

import jakarta.persistence.*;

@Entity
@Table(name = "exam_student")
public class ExamStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id")
    private Long examId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "slot_id") // 🔥 ADD THIS
    private Long slotId;

    public Long getId() {
        return id;
    }

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    // ✅ NEW GETTER
    public Long getSlotId() {
        return slotId;
    }

    // ✅ NEW SETTER
    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }
}