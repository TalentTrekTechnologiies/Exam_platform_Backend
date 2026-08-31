-- ============================================================================
--  Draw papers from a bank, and know who is actually still there
-- ============================================================================
--  Two unrelated columns, added in the same release.
--
--  sections.questions_to_draw
--
--  How many of a section's questions each candidate sits. Null means all of
--  them, which is what every exam did before this existed and what most still
--  want. Set it lower and the section becomes a pool: two hundred Physics
--  questions imported, twenty on each paper, drawn per candidate and frozen
--  with the rest of the paper. Neighbours then sit different questions rather
--  than the same set in a different order.
--
--  attempts.last_seen_at
--
--  When a candidate's browser last spoke to the server. The live monitor used
--  to infer this from the newest saved ANSWER, which is not the same thing --
--  somebody reading a comprehension passage, or working a problem out on
--  paper, saves nothing for minutes and was reported as DISCONNECTED while
--  writing perfectly normally. Invigilators were being sent to desks with
--  nothing wrong, during a live sitting.
--
--  The paper polls the clock every twenty seconds whatever the candidate is
--  doing, and that poll now stamps this column.
--
--  On a FRESH install ddl-auto=update creates both on first boot and this file
--  is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

ALTER TABLE sections
    ADD COLUMN questions_to_draw INT NULL;

ALTER TABLE attempts
    ADD COLUMN last_seen_at DATETIME(6) NULL;

-- ── Attempts already in flight ──────────────────────────────────────────────
--
-- Seed the column so a sitting that is live while this runs does not have its
-- whole hall turn DISCONNECTED for the first twenty seconds after restart.
-- Anything already submitted is left null; it is never read for those.

UPDATE attempts
   SET last_seen_at = NOW()
 WHERE status <> 'SUBMITTED';

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM sections LIKE 'questions_to_draw';
--      SHOW COLUMNS FROM attempts LIKE 'last_seen_at';
--      SELECT id, name, questions_to_draw FROM sections ORDER BY id DESC LIMIT 10;
-- ============================================================================
