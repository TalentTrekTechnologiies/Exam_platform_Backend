package com.Exam.Exam_System.service;

import com.Exam.Exam_System.repository.AttemptRepository;
import com.Exam.Exam_System.repository.ExamRepository;
import com.Exam.Exam_System.repository.ExamStudentRepository;
import com.Exam.Exam_System.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Returning the time a disconnection cost.
 *
 * The grant itself is one SQL statement and its arithmetic belongs to the
 * database, so what is pinned here is the part that is easy to get wrong in
 * Java and silent when it is: that the cap actually reaches the statement, and
 * that the cached deadline is thrown away when the real one moves.
 *
 * Both failures look identical from the outside — a candidate who was
 * disconnected and got nothing back — and neither raises anything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptReconnectTest {

    @Mock AttemptRepository attemptRepository;
    @Mock ExamRepository examRepository;
    @Mock SlotRepository slotRepository;
    @Mock PaperService paperService;
    @Mock ScoringService scoringService;
    @Mock AttemptCache attemptCache;
    @Mock ExamStudentRepository examStudentRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock SectionClockService sectionClock;

    AttemptService attempts;

    private static final long ATTEMPT = 77L;
    private static final int GRACE = 60;
    private static final int CAP = 900;

    @BeforeEach
    void setUp() throws Exception {
        attempts = new AttemptService(attemptRepository, examRepository, slotRepository,
                paperService, scoringService, attemptCache, examStudentRepository, jdbcTemplate,
                sectionClock);
        // @Value fields are Spring's job; this is a plain unit test.
        set("reconnectGraceSeconds", GRACE);
        set("reconnectMaxSeconds", CAP);
    }

    private void set(String field, int value) throws Exception {
        Field f = AttemptService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(attempts, value);
    }

    @Test
    @DisplayName("A reconnection after a real gap returns time and drops the cached deadline")
    void grantsAndInvalidates() {
        // The grant statement matched: this candidate was away long enough.
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        attempts.markSeen(ATTEMPT);

        // Without this the deadline moves in the database and the candidate is
        // served the old one from cache until it happens to be evicted — they
        // are given the time and never see it.
        verify(attemptCache).invalidate(ATTEMPT);
    }

    @Test
    @DisplayName("The cap and the grace period reach the statement")
    void capIsApplied() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        attempts.markSeen(ATTEMPT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(sql.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());

        // The ceiling is what stops a candidate farming the allowance by
        // disconnecting over and over. If it ever stops being passed, the
        // feature becomes "stop your clock whenever you like".
        assertTrue(sql.getValue().contains("extended_seconds"),
                "the grant statement must account against the allowance");
        java.util.List<Object> passed = args.getAllValues();
        assertEquals(CAP, passed.get(0), "remaining allowance is measured against the cap");
        assertEquals(CAP, passed.get(1), "the increment is capped too");
        assertEquals(ATTEMPT, passed.get(2));
        assertEquals(GRACE, passed.get(3), "a silence shorter than the grace period is not a disconnection");
        assertEquals(CAP, passed.get(4), "a candidate at the cap gets nothing further");
    }

    @Test
    @DisplayName("An ordinary poll only stamps the heartbeat and leaves the cache alone")
    void ordinaryPollDoesNotInvalidate() {
        // No gap: the grant statement matches nothing, the plain heartbeat runs.
        // The grant matches nothing; the plain heartbeat that follows returns
        // whatever it likes, because markSeen does not read it.
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);

        attempts.markSeen(ATTEMPT);

        // Evicting on every poll would defeat the cache the clock is served
        // from — a database read per candidate every twenty seconds.
        verify(attemptCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("A database fault during the heartbeat never reaches the candidate")
    void databaseFaultIsSwallowed() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        // A missed heartbeat is an invigilator seeing a stale status for twenty
        // seconds. An exception here is the candidate's clock poll failing,
        // which is far worse and is the whole reason this is caught.
        assertDoesNotThrow(() -> attempts.markSeen(ATTEMPT));
    }
}
