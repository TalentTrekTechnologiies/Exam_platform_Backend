package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Slot;
import com.Exam.Exam_System.repository.SlotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class SlotService {

    private final SlotRepository slotRepository;

    public SlotService(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    public Slot createSlot(Slot slot) {
        return slotRepository.save(slot);
    }

    public boolean isExamActive(Long examId) {
        LocalDateTime now = LocalDateTime.now();

        Optional<Slot> slotOpt = slotRepository
                .findByExamIdAndStartTimeLessThanEqualAndEndTimeGreaterThanEqual(
                        examId, now, now
                );

        if (slotOpt.isEmpty()) {
            return false;
        }

        Slot slot = slotOpt.get();
        
        // Note: You have a comment for "Allow only first 15 mins" but the logic 
        // below currently checks the full duration. 
        // If you want a hard cutoff for entry, you can use: now.isBefore(slot.getStartTime().plusMinutes(15))
        return now.isBefore(slot.getEndTime());
    }

    // ✅ REPLACED: Updated to use List and java.time.Duration
 // ADD THIS - duration now comes from Exam, not Slot
 // SlotService only needs to know about slot timing (access window)
 // Duration is fetched directly from ExamRepository in StudentController
 // No changes needed here - method is simply removed/unused
    public Slot getSlotById(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));
    }
}