-- ============================================================================
--  Hold results until the college announces them
-- ============================================================================
--  WHY
--  A score shown the instant a candidate presses submit cannot be taken back.
--  It is around the hall before an invigilator has read a single malpractice
--  report, and a key corrected afterwards means every candidate has already
--  seen a mark that no longer stands.
--
--  These two columns decide whether candidates may see their own scorecard.
--  Separate from `published`, and deliberately so: publishing decides whether a
--  paper can be SAT, this decides whether the marks are OUT. Staff see every
--  score on the Results page throughout either way — the hold exists so a
--  college can moderate before it announces, not to keep anything from the
--  people running the exam.
--
--  On a FRESH install ddl-auto=update creates these on first boot and this file
--  is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

ALTER TABLE exams
    ADD COLUMN results_released BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN results_released_at DATETIME(6) NULL;

-- ── Exams that already finished ─────────────────────────────────────────────
--
-- The column defaults to "held", which is right for every exam from here on.
-- It is wrong for exams already sat and already seen: a candidate who read
-- their scorecard last week would open the link today and be told results have
-- not been announced, which reads as the platform having lost their paper.
--
-- So anything already submitted against is released, matching what those
-- candidates have in fact already been shown. Uncomment if this database has
-- results that candidates have seen; leave commented for a college that has
-- only ever run rehearsals.
--
-- UPDATE exams e
--    SET e.results_released = b'1',
--        e.results_released_at = NOW()
--  WHERE EXISTS (SELECT 1 FROM attempts a
--                 WHERE a.exam_id = e.id AND a.status = 'SUBMITTED');

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM exams LIKE 'results_%';
--      SELECT id, title, published, results_released FROM exams ORDER BY id DESC LIMIT 10;
-- ============================================================================
