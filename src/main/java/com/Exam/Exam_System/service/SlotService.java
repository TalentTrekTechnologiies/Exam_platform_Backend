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
    /**
     * The slot, if it still exists. A candidate's enrolment can outlive the
     * slot it names (one deleted before the delete was refused), and sign-in
     * must say so plainly rather than fail with a server error.
     */
    public Optional<Slot> findSlot(Long slotId) {
        return slotId == null ? Optional.empty() : slotRepository.findById(slotId);
    }

    /**
     * The sitting a candidate belongs in when the one they were enrolled in
     * was deleted: the one of their exam that is open for entry right now,
     * else the next one to open. Null when the exam has none still to come.
     *
     * Someone who arrives during a sitting's entry window is there for that
     * sitting; that is the only evidence left once their own slot is gone,
     * and it is the same evidence an invigilator at the door would use.
     */
    public Slot sittingByClock(Long examId, LocalDateTime now, long lobbyMinutes) {
        Slot openNow = null;
        Slot next = null;
        for (Slot s : slotRepository.findAllByExamId(examId)) {
            if (s.getStartTime() == null || s.getEndTime() == null) continue;
            boolean doorsOpen = !now.isBefore(s.getStartTime().minusMinutes(lobbyMinutes));
            boolean stillEntering = !now.isAfter(s.getEndTime());
            if (doorsOpen && stillEntering) {
                // Overlapping sittings: the one that began most recently.
                if (openNow == null || s.getStartTime().isAfter(openNow.getStartTime())) openNow = s;
            } else if (!doorsOpen && (next == null || s.getStartTime().isBefore(next.getStartTime()))) {
                next = s;
            }
        }
        return openNow != null ? openNow : next;
    }

    public Slot getSlotById(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));
    }
}