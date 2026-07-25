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

    @DeleteMapping("/{id}")
    public void deleteSlot(@PathVariable Long id) {
        accessGuard.requireOwnedSlot(id);
        slotRepository.deleteById(id);
    }
}
