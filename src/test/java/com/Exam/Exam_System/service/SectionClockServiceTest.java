package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.Attempt;
import com.Exam.Exam_System.Entity.Section;
import com.Exam.Exam_System.repository.AttemptRepository;
import com.Exam.Exam_System.repository.SectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Sectional timing — the lock, which is the whole feature.
 *
 * A "sectional" paper a candidate can still write into after the section closed
 * is not sectional, and the failure is silent: everything looks right, marks
 * come out, and one candidate had forty minutes on the coding problem where
 * everybody else had twenty. So these pin the boundary in both directions —
 * that a closed section refuses, and that an open one does not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SectionClockServiceTest {

    @Mock SectionRepository sectionRepository;
    @Mock AttemptRepository attemptRepository;

    SectionClockService clock;

    @BeforeEach
    void setUp() {
        clock = new SectionClockService(sectionRepository, attemptRepository);
        when(attemptRepository.save(any(Attempt.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Section section(long id, String name, Integer minutes) {
        Section s = new Section();
        s.setName(name);
        s.setExamId(1L);
        s.setDurationMinutes(minutes);
        try {
            var f = Section.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    private Attempt attempt() {
        Attempt a = new Attempt();
        a.setExamId(1L);
        a.setStatus("STARTED");
        a.setStartTime(LocalDateTime.now().minusMinutes(5));
        a.setEndTime(LocalDateTime.now().plusHours(3));
        try {
            var f = Attempt.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, 900L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    private void givenThreeTimedSections() {
        when(sectionRepository.findByExamId(anyLong())).thenReturn(List.of(
                section(1, "Aptitude", 20),
                section(2, "Verbal", 15),
                section(3, "Coding", 45)
        ));
    }

    // ── whether the paper is sectional at all ───────────────────────────────

    @Test
    @DisplayName("Every section timed makes the paper sectional")
    void allTimedIsSectional() {
        givenThreeTimedSections();
        assertTrue(clock.isSectional(1L));
    }

    @Test
    @DisplayName("A duration on only some sections is not a sectional paper")
    void partiallyTimedIsNotSectional() {
        // Half-specified is the dangerous state: it reads as sectional to
        // whoever set it and behaves as an ordinary paper. Publication blocks
        // it; this makes sure nothing here treats it as locked in the meantime.
        when(sectionRepository.findByExamId(anyLong())).thenReturn(List.of(
                section(1, "Aptitude", 20),
                section(2, "Verbal", null)
        ));
        assertFalse(clock.isSectional(1L));
        assertTrue(clock.timedSectionsFor(1L).isEmpty());
    }

    @Test
    @DisplayName("An exam with no sections at all is not sectional")
    void noSectionsIsNotSectional() {
        when(sectionRepository.findByExamId(anyLong())).thenReturn(List.of());
        assertFalse(clock.isSectional(1L));
    }

    // ── the boundary ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Starting opens the first section, not any other")
    void firstSectionOpens() {
        givenThreeTimedSections();
        Attempt a = attempt();

        clock.beginFirstSection(a);

        assertEquals(1L, a.getCurrentSectionId());
        assertNotNull(a.getSectionEndsAt());
    }

    @Test
    @DisplayName("A section still running is not advanced past")
    void openSectionStays() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(1L);
        a.setSectionEndsAt(LocalDateTime.now().plusMinutes(5));

        clock.advanceIfDue(a);

        assertEquals(1L, a.getCurrentSectionId(), "a section with time left must not close");
    }

    @Test
    @DisplayName("A section whose time is up hands over to the next")
    void expiredSectionAdvances() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(1L);
        a.setSectionEndsAt(LocalDateTime.now().minusSeconds(5));

        clock.advanceIfDue(a);

        assertEquals(2L, a.getCurrentSectionId());
    }

    @Test
    @DisplayName("A candidate away for two whole sections lands in the third, not the second")
    void skipsWholeSectionsMissed() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(1L);
        // Away for 40 minutes: section one (20) and section two (15) are both
        // gone. Advancing once would put them in a section that has also ended.
        a.setSectionEndsAt(LocalDateTime.now().minusMinutes(40));

        clock.advanceIfDue(a);

        assertEquals(3L, a.getCurrentSectionId(), "each missed section is closed in turn");
    }

    @Test
    @DisplayName("Time missed while away is not handed back as extra section time")
    void nextSectionRunsFromWhenThePreviousClosed() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(1L);
        // Section one ended ten minutes ago. Section two is fifteen minutes
        // long, so five remain — not fifteen. Measuring from "now" would pay a
        // candidate for having been absent.
        a.setSectionEndsAt(LocalDateTime.now().minusMinutes(10));

        clock.advanceIfDue(a);

        long left = java.time.Duration.between(LocalDateTime.now(), a.getSectionEndsAt()).toMinutes();
        assertTrue(left <= 5, "expected about 5 minutes left, got " + left);
    }

    @Test
    @DisplayName("The last section closing leaves the paper's own clock in charge")
    void lastSectionEndsCleanly() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(3L);
        a.setSectionEndsAt(LocalDateTime.now().minusSeconds(5));

        clock.advanceIfDue(a);

        assertNull(a.getSectionEndsAt(), "nothing is left to open after the last section");
    }

    @Test
    @DisplayName("A section may not outlive the paper it is part of")
    void sectionCannotRunPastTheDeadline() {
        // Sections summing past the exam's duration would otherwise leave the
        // last one counting down to a moment already gone.
        when(sectionRepository.findByExamId(anyLong())).thenReturn(List.of(
                section(1, "Only", 180)
        ));
        Attempt a = attempt();
        a.setEndTime(LocalDateTime.now().plusMinutes(30));

        clock.beginFirstSection(a);

        assertFalse(a.getSectionEndsAt().isAfter(a.getEndTime()),
                "the section outlived the paper");
    }

    // ── what may be written to ──────────────────────────────────────────────

    @Test
    @DisplayName("The open section accepts answers; a closed one does not")
    void onlyTheOpenSectionAcceptsAnswers() {
        Attempt a = attempt();
        a.setCurrentSectionId(2L);

        assertTrue(clock.isOpen(a, 2L), "the section they are in must accept answers");
        assertFalse(clock.isOpen(a, 1L), "a closed section must refuse — this is the whole feature");
        assertFalse(clock.isOpen(a, 3L), "a section not yet reached must refuse too");
    }

    @Test
    @DisplayName("On an ordinary paper every question stays writable")
    void ordinaryPaperIsAlwaysOpen() {
        Attempt a = attempt();   // no current section: not a sectional exam

        assertTrue(clock.isOpen(a, 1L));
        assertTrue(clock.isOpen(a, null), "an unsectioned question is fine on an unsectioned paper");
    }

    @Test
    @DisplayName("An unsectioned question on a sectional paper is refused rather than let through")
    void unsectionedQuestionOnSectionalPaperIsRefused() {
        // It belongs to no section, so no section's clock governs it. Accepting
        // it would be a hole straight through the lock, writable for the whole
        // sitting.
        Attempt a = attempt();
        a.setCurrentSectionId(1L);

        assertFalse(clock.isOpen(a, null));
    }

    @Test
    @DisplayName("The candidate is told which sections are done, open and still to come")
    void stateNamesEverySection() {
        givenThreeTimedSections();
        Attempt a = attempt();
        a.setCurrentSectionId(2L);
        a.setSectionEndsAt(LocalDateTime.now().plusMinutes(10));

        Map<String, Object> state = clock.stateFor(a);

        assertEquals(true, state.get("sectional"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) state.get("sections");
        assertEquals("CLOSED", sections.get(0).get("state"));
        assertEquals("OPEN", sections.get(1).get("state"));
        assertEquals("UPCOMING", sections.get(2).get("state"));
    }
}
