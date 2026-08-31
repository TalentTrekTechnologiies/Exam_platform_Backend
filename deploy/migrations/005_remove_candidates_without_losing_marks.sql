-- ============================================================================
--  Take a candidate off the roll without erasing what they scored
-- ============================================================================
--  WHY
--  Rosters accumulate mistakes: a name typed twice, a batch uploaded against
--  the wrong exam, a candidate who never enrolled. Staff need to clear those
--  out, and until now the only way was to leave them there.
--
--  A real DELETE cannot be the answer. Every results query reads the hall
--  ticket and the name from `students`:
--
--      FROM exam_student es JOIN students s ON s.id = es.student_id
--                           LEFT JOIN attempts a ON ...
--
--  so removing the row removes the candidate from every report they appear in.
--  An admin tidying up duplicates would silently destroy the record of exams
--  that were actually sat — marks that a college has to be able to produce
--  months later.
--
--  So a removed candidate is marked, not deleted. They leave the student list,
--  they leave any exam they have not sat, and they can no longer sign in. The
--  rows their marks hang from are untouched, and Results shows exactly what it
--  showed the day before.
--
--  Re-enrolling the same hall ticket revives the original person rather than
--  creating a second one — which the unique key on (admin_id, hall_ticket)
--  requires anyway, and which is what keeps their history attached.
--
--  On a FRESH install ddl-auto=update creates this on first boot and this file
--  is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

ALTER TABLE students
    ADD COLUMN deleted_at DATETIME(6) NULL;

-- ── Everyone currently on the books stays on it ──────────────────────────────
--
-- NULL means "not removed", so the new column is already correct for every
-- existing row and there is no backfill to do. Stated rather than assumed,
-- because the opposite default would hide a college's entire roster.

-- ── Why there is no index ───────────────────────────────────────────────────
--
-- The registry query filters on `deleted_at IS NULL` but always alongside
-- `admin_id = ?`, which is the selective half — one institution's candidates,
-- not the platform's. A separate index on a column that is NULL for almost
-- every row would be read past rather than used. Add one only if a college
-- ever accumulates enough removals for it to matter.

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM students LIKE 'deleted_at';
--      SELECT COUNT(*) AS on_the_books FROM students WHERE deleted_at IS NULL;
--      SELECT COUNT(*) AS removed      FROM students WHERE deleted_at IS NOT NULL;
--
--  And that no marks moved — this must return the same number before and after
--  any removal:
--      SELECT COUNT(*) FROM attempts WHERE status <> 'PENDING';
-- ============================================================================
