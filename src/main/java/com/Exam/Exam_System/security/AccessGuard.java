package com.Exam.Exam_System.security;

import com.Exam.Exam_System.Entity.*;
import com.Exam.Exam_System.repository.*;
import com.Exam.Exam_System.service.AttemptCache;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Every ownership check in one place.
 *
 * Two distinct problems this solves:
 *
 *  1. Tenancy — one college must not read or edit another college's exams,
 *     questions, sections or candidates.
 *  2. IDOR on the student side — attempt ids are sequential, so without a check
 *     a candidate could fetch /student/paper/{id-1} and read a neighbour's
 *     paper, responses and scorecard.
 */
@Component
public class AccessGuard {

    private final CurrentUser currentUser;
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final SectionRepository sectionRepository;
    private final SlotRepository slotRepository;
    private final AttemptRepository attemptRepository;
    private final AttemptCache attemptCache;

    public AccessGuard(CurrentUser currentUser,
                       ExamRepository examRepository,
                       QuestionRepository questionRepository,
                       SectionRepository sectionRepository,
                       SlotRepository slotRepository,
                       AttemptRepository attemptRepository,
                       AttemptCache attemptCache) {
        this.currentUser = currentUser;
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.sectionRepository = sectionRepository;
        this.slotRepository = slotRepository;
        this.attemptRepository = attemptRepository;
        this.attemptCache = attemptCache;
    }

    // ── Admin side ───────────────────────────────────────────────────────────

    /** The exam, if the signed-in admin owns it. */
    public Exam requireOwnedExam(Long examId) {
        Long adminId = currentUser.adminId();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("Exam not found"));

        // Legacy rows created before tenancy existed have no owner. Claim them for
        // the first admin who touches them rather than bricking existing data.
        if (exam.getAdminId() == null) {
            exam.setAdminId(adminId);
            return examRepository.save(exam);
        }

        if (!exam.getAdminId().equals(adminId)) {
            throw new AccessDeniedException("That exam belongs to another institution.");
        }
        return exam;
    }

    public Question requireOwnedQuestion(Long questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new NoSuchElementException("Question not found"));
        requireOwnedExam(question.getExamId());
        return question;
    }

    public Section requireOwnedSection(Long sectionId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new NoSuchElementException("Section not found"));
        requireOwnedExam(section.getExamId());
        return section;
    }

    public Slot requireOwnedSlot(Long slotId) {
        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NoSuchElementException("Slot not found"));
        requireOwnedExam(slot.getExamId());
        return slot;
    }

    // ── Student side ─────────────────────────────────────────────────────────

    /**
     * Confirms the attempt belongs to the signed-in candidate.
     *
     * Ownership is immutable, so this is answered from the attempt cache and
     * normally costs no query at all — which matters because it guards the
     * most-polled endpoints in the system.
     */
    public void requireOwnAttempt(Long attemptId) {
        Long studentId = currentUser.studentId();
        AttemptCache.AttemptMeta meta = attemptCache.get(attemptId);

        if (!meta.studentId().equals(studentId)) {
            // Never lock a candidate out of their own exam on the word of a cache.
            // Re-read from the database before refusing: if ids were ever reused
            // beneath us (a restore, a reset), the cached owner could be stale,
            // and the cost of being wrong here is a candidate who cannot sit.
            // One extra query on a path that should essentially never be taken.
            attemptCache.invalidate(attemptId);
            meta = attemptCache.get(attemptId);

            if (!meta.studentId().equals(studentId)) {
                throw new AccessDeniedException("That attempt belongs to another candidate.");
            }
        }
    }

    /** The full attempt record, for the rare paths that need more than ownership. */
    public Attempt requireOwnAttemptEntity(Long attemptId) {
        requireOwnAttempt(attemptId);
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Attempt not found"));
    }

    /** Guards against a token minted for exam A being replayed against exam B. */
    public void requireTokenMatchesExam(Long examId) {
        AuthPrincipal principal = currentUser.get();
        if (principal.isAdmin()) return;
        if (principal.examId() != null && !principal.examId().equals(examId)) {
            throw new AccessDeniedException("Your session is not valid for this exam.");
        }
    }
}
