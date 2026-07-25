package com.Exam.Exam_System.repository;

import com.Exam.Exam_System.Entity.Slot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    Optional<Slot> findByExamIdAndStartTimeLessThanEqualAndEndTimeGreaterThanEqual(
            Long examId,
            LocalDateTime now1,
            LocalDateTime now2
    );
    List<Slot> findAllByExamId(Long examId);
}