-- ============================================================================
--  Sectional timing — the TCS NQT shape of paper
-- ============================================================================
--  An ordinary paper has one clock and free navigation: a candidate moves
--  between Physics and Chemistry as they like until the whole thing ends.
--
--  A sectional paper is a sequence of separately timed sections, sat in order,
--  and a section whose time is up is CLOSED — not hidden, not discouraged,
--  closed, with its answers final. That lock is the entire point: the format
--  exists to stop a candidate spending the aptitude section's time on the
--  coding problem.
--
--  ALL OR NOTHING. A paper is sectional only when EVERY section carries a
--  duration. A duration on two sections of three reads as sectional to whoever
--  set it and behaves as an ordinary paper — the worst of both, and the reason
--  publication now refuses it rather than letting it be discovered mid-sitting.
--
--  Nothing here changes an existing exam. Every column is nullable, and with
--  them null the paper has one clock and free navigation exactly as before.
--
--  On a FRESH install ddl-auto=update creates these on first boot and this file
--  is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

-- Minutes for this section alone. Null on any section of an exam means that
-- exam is not sectional.
ALTER TABLE sections
    ADD COLUMN duration_minutes INT NULL;

-- Where each candidate currently is, and when that section closes.
--
-- Server-owned, for the same reason the paper's own deadline is: a sectional
-- lock whose timing the candidate's browser could decide is not a lock. The
-- clock poll advances it, so a candidate who closes their laptop during section
-- one does not find section two waiting for them when they come back.
ALTER TABLE attempts
    ADD COLUMN current_section_id BIGINT NULL,
    ADD COLUMN section_ends_at    DATETIME(6) NULL;

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM sections LIKE 'duration_minutes';
--      SHOW COLUMNS FROM attempts LIKE 'current_section_id';
--      SHOW COLUMNS FROM attempts LIKE 'section_ends_at';
--
--      -- Nothing should be sectional yet:
--      SELECT exam_id, COUNT(*) AS sections,
--             SUM(duration_minutes IS NOT NULL) AS timed
--        FROM sections GROUP BY exam_id;
--
--  TO MAKE A PAPER SECTIONAL
--      Set a duration on every one of its sections, from the Sections screen.
--      Publication warns if they do not add up to the exam's own duration, and
--      refuses if only some of them are set.
-- ============================================================================
