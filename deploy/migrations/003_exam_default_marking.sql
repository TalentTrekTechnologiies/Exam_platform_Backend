-- ============================================================================
--  Persist each exam's default marking scheme
-- ============================================================================
--  WHY
--  The Create Exam screen has always asked for "marks per question" and
--  "penalty per wrong answer" — and the server had nowhere to put them. They
--  were sent, silently ignored, and survived only in the creating browser's
--  local storage.
--
--  The consequence was quiet and expensive: an admin who set 4 marks with a
--  −1 penalty, then imported a question paper from CSV or PDF, got questions
--  worth 1 mark each with no penalty at all. Nothing on screen said the scheme
--  had been discarded. The mismatch only became visible after results were
--  published against the wrong marking.
--
--  Questions still win when they state their own marks — a document that says
--  "[4 marks]" is more specific than an exam-wide default and should override
--  it. These columns are only the fallback for questions that say nothing.
--
--  On a FRESH install ddl-auto=update creates these on first boot and this
--  file is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

ALTER TABLE exams
    ADD COLUMN default_marks INT NULL,
    ADD COLUMN default_negative_marks DOUBLE NULL;

-- Existing exams keep behaving exactly as before: NULL means "no exam-level
-- default", so ScoringService's own fallbacks (1 mark, no penalty) still apply
-- to any question that never declared marks. Nothing is retroactively
-- re-marked, which matters if results have already been published.

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM exams LIKE 'default_%';
-- ============================================================================
