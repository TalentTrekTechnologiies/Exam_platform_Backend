package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Slot;
import com.Exam.Exam_System.repository.SlotRepository;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.service.SlotService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/slot")
public class SlotController {

    private final SlotService slotService;
    private final SlotRepository slotRepository;
    private final AccessGuard accessGuard;

    public SlotController(SlotService slotService,
                          SlotRepository slotRepository,
                          AccessGuard accessGuard) {
        this.slotService = slotService;
        this.slotRepository = slotRepository;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public Slot createSlot(@RequestBody Slot slot) {
        accessGuard.requireOwnedExam(slot.getExamId());

        if (slot.getStartTime() == null || slot.getEndTime() == null) {
            throw new IllegalArgumentException("A slot needs both a start and an end time.");
        }
        if (!slot.getEndTime().isAfter(slot.getStartTime())) {
            throw new IllegalArgumentException("The slot must end after it starts.");
        }
        return slotService.createSlot(slot);
    }

    @GetMapping("/{examId}")
    public List<Slot> getSlots(@PathVariable Long examId) {
        accessGuard.requireOwnedExam(examId);
        return slotRepository.findAllByExamId(examId);
    }

    /**
     * Moves a slot's window.
     *
     * A sitting rescheduled at short notice is ordinary — a hall double-booked,
     * a power cut, a batch running late. Without this the only way to change a
     * time was to delete the slot and make another, which detaches every
     * candidate already enrolled against it and is a far bigger operation than
     * the change deserves.
     *
     * A window that has already closed can be reopened, deliberately. That is
     * the most common reason anybody edits a slot at all: it was set for this
     * morning and the morning has gone.
     */
    @PutMapping("/{id}")
    public Slot updateSlot(@PathVariable Long id, @RequestBody Slot updated) {
        Slot slot = accessGuard.requireOwnedSlot(id);

        if (updated.getStartTime() == null || updated.getEndTime() == null) {
            throw new IllegalArgumentException("A slot needs both a start and an end time.");
        }
        if (!updated.getEndTime().isAfter(updated.getStartTime())) {
            throw new IllegalArgumentException("The slot must end after it starts.");
        }

        slot.setStartTime(updated.getStartTime());
        slot.setEndTime(updated.getEndTime());
        return slotRepository.save(slot);
    }

    @DeleteMapping("/{id}")
    public void deleteSlot(@PathVariable Long id) {
        accessGuard.requireOwnedSlot(id);
        slotRepository.deleteById(id);
    }
}
