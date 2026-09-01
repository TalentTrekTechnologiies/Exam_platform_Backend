package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.repository.AttemptRepository;
import com.Exam.Exam_System.repository.SectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sectional timing — the TCS NQT shape of paper.
 *
 * An ordinary paper has one clock and free navigation: a candidate moves
 * between Physics and Chemistry as they like until the whole thing ends. A
 * sectional paper is a sequence of separately timed sections, sat in order,
 * and a section whose time is up is CLOSED. Not hidden, not discouraged —
 * closed, with its answers final.
 *
 * That lock is the entire point. A "sectional" exam a candidate can drift back
 * into is not sectional, and the format exists precisely to stop somebody
 * spending the aptitude section's time on the coding problem.
 *
 * Which section a candidate is in, and when it closes, are the server's to
 * decide for the same reason the paper's deadline is. A browser that could
 * choose its own section boundary could choose not to have one.
 *
 * Off unless every section of an exam carries a duration. A paper with
 * durations on some sections and not others is a half-specified exam rather
 * than a feature, and is treated as having none.
 */
@Service
public class SectionClockService {

    private static final Logger log = LoggerFactory.getLogger(SectionClockService.class);

    private final SectionRepository sectionRepository;
    private final AttemptRepository attemptRepository;

    public SectionClockService(SectionRepository sectionRepository,
                               AttemptRepository attemptRepository) {
        this.sectionRepository = sectionRepository;
        this.attemptRepository = attemptRepository;
    }

    /**
     * The sections of a sectional exam, in the order they are sat.
     *
     * Empty when the exam is not sectional. Ordered by id, which is the same
     * order the paper is built in — a candidate's section order and their
     * question order have to agree or the lock closes the wrong block.
     */
    @Transactional(readOnly = true)
    public List<Section> timedSectionsFor(Long examId) {
        List<Section> all = new ArrayList<>(sectionRepository.findByExamId(examId));
        if (all.isEmpty()) return List.of();

        boolean everySectionTimed = all.stream()
                .allMatch(s -> s.getDurationMinutes() != null && s.getDurationMinutes() > 0);
        if (!everySectionTimed) return List.of();

        all.sort(Comparator.comparing(Section::getId));
        return all;
    }

    /** Whether this exam is sat section by section. */
    @Transactional(readOnly = true)
    public boolean isSectional(Long examId) {
        return !timedSectionsFor(examId).isEmpty();
    }

    /**
     * Opens the first section as the candidate starts.
     *
     * Called at the same moment the paper's own clock starts. A candidate who
     * never reaches this — an ordinary exam — carries nulls and is unaffected
     * by everything below.
     */
    @Transactional
    public void beginFirstSection(Attempt attempt) {
        List<Section> sections = timedSectionsFor(attempt.getExamId());
        if (sections.isEmpty()) return;

        Section first = sections.get(0);
        attempt.setCurrentSectionId(first.getId());
        attempt.setSectionEndsAt(cappedEnd(attempt, LocalDateTime.now(), first));
        attemptRepository.save(attempt);
        log.info("Attempt {} begins section {} ({} min).",
                attempt.getId(), first.getName(), first.getDurationMinutes());
    }

    /**
     * Closes any section whose time is up and opens the next.
     *
     * Driven by the clock poll rather than a scheduler: the candidate's own
     * browser asks every twenty seconds, so the boundary lands within that of
     * being exact, without a background job walking every live attempt.
     *
     * Loops rather than advancing once, because a candidate who was away for
     * long enough may have missed a whole section. Their answers to it stand;
     * they simply have no more time in it.
     *
     * @return the attempt, with its section state current.
     */
    @Transactional
    public Attempt advanceIfDue(Attempt attempt) {
        if (attempt.getCurrentSectionId() == null || attempt.getSectionEndsAt() == null) return attempt;
        if ("SUBMITTED".equals(attempt.getStatus())) return attempt;

        List<Section> sections = timedSectionsFor(attempt.getExamId());
        if (sections.isEmpty()) return attempt;

        LocalDateTime now = LocalDateTime.now();
        boolean moved = false;

        while (attempt.getSectionEndsAt() != null && !now.isBefore(attempt.getSectionEndsAt())) {
            int index = indexOf(sections, attempt.getCurrentSectionId());
            if (index < 0 || index >= sections.size() - 1) {
                // The last section has closed. The paper's own deadline takes
                // over from here; nothing else is left to open.
                attempt.setSectionEndsAt(null);
                moved = true;
                break;
            }

            Section next = sections.get(index + 1);
            // From the moment the previous section CLOSED, not from now: a
            // candidate whose browser was away must not be handed the time
            // their absence cost them a second time over.
            LocalDateTime opensAt = attempt.getSectionEndsAt();
            attempt.setCurrentSectionId(next.getId());
            attempt.setSectionEndsAt(cappedEnd(attempt, opensAt, next));
            moved = true;

            log.info("Attempt {} moved on to section {}.", attempt.getId(), next.getName());
        }

        return moved ? attemptRepository.save(attempt) : attempt;
    }

    /**
     * Whether this section is still open to answers.
     *
     * The question the answer endpoint asks before accepting anything. On a
     * paper that is not sectional, everything is open.
     */
    @Transactional(readOnly = true)
    public boolean isOpen(Attempt attempt, Long sectionId) {
        if (attempt.getCurrentSectionId() == null) return true;      // not a sectional paper
        if (sectionId == null) return false;                          // unsectioned question on a sectional paper
        return sectionId.equals(attempt.getCurrentSectionId());
    }

    /** What the candidate's clock needs to know about their sections. */
    @Transactional(readOnly = true)
    public Map<String, Object> stateFor(Attempt attempt) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Section> sections = timedSectionsFor(attempt.getExamId());
        if (sections.isEmpty()) {
            out.put("sectional", false);
            return out;
        }

        int current = indexOf(sections, attempt.getCurrentSectionId());
        out.put("sectional", true);
        out.put("currentSectionId", attempt.getCurrentSectionId());
        out.put("sectionRemainingSeconds", attempt.getSectionEndsAt() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), attempt.getSectionEndsAt()).getSeconds()));

        // Every section and whether it is done, open or still to come, so the
        // paper can grey out what is closed rather than letting a candidate
        // click into it and be refused.
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sectionId", s.getId());
            row.put("name", s.getName());
            row.put("durationMinutes", s.getDurationMinutes());
            row.put("state", current < 0 ? "UPCOMING"
                    : i < current ? "CLOSED" : i == current ? "OPEN" : "UPCOMING");
            all.add(row);
        }
        out.put("sections", all);
        return out;
    }

    private int indexOf(List<Section> sections, Long sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getId().equals(sectionId)) return i;
        }
        return -1;
    }

    /**
     * A section may not outlive the paper.
     *
     * Without this, sections summing to more than the exam's duration would let
     * the last one run past the candidate's own deadline — a clock counting
     * down to a moment that has already passed.
     */
    private LocalDateTime cappedEnd(Attempt attempt, LocalDateTime from, Section section) {
        LocalDateTime end = from.plusMinutes(section.getDurationMinutes());
        LocalDateTime paperEnd = attempt.getEndTime();
        return (paperEnd != null && end.isAfter(paperEnd)) ? paperEnd : end;
    }
}
