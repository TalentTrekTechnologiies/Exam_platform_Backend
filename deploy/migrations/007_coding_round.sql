-- ============================================================================
--  The coding round
-- ============================================================================
--  A question may now be a programming problem rather than four options, and
--  an answer may be a program rather than a letter.
--
--  Nothing here changes an existing exam. `questions.type` defaults to 'MCQ',
--  so every question already in this database stays exactly what it was, and
--  every column added below is nullable and unused until somebody authors a
--  coding question.
--
--  ONE CHANGE IS NOT ADDITIVE, and it is the reason to read this before
--  running it: questions.question_text becomes TEXT. It was VARCHAR(255),
--  which cannot hold a problem statement -- and was already a hazard for a
--  long comprehension question, silently truncating at 255 characters on the
--  way in. Widening a column keeps every existing row; it cannot be narrowed
--  again afterwards without losing anything longer, so take the dump first.
--
--  On a FRESH install ddl-auto=update creates all of this on first boot and
--  this file is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

-- ── Questions ───────────────────────────────────────────────────────────────

ALTER TABLE questions
    MODIFY COLUMN question_text TEXT;

ALTER TABLE questions
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'MCQ',
    ADD COLUMN constraints_text   TEXT NULL,
    ADD COLUMN sample_input       TEXT NULL,
    ADD COLUMN sample_output      TEXT NULL,
    ADD COLUMN sample_explanation TEXT NULL,
    -- Comma-separated language ids: c, cpp, java, python. Null means every
    -- language the judge supports.
    ADD COLUMN allowed_languages  VARCHAR(255) NULL,
    ADD COLUMN time_limit_ms      INT NULL,
    ADD COLUMN memory_limit_mb    INT NULL,
    ADD COLUMN starter_code       TEXT NULL;

-- Explicit, rather than relying on the DEFAULT to have applied. A row written
-- by an older application version during the upgrade window would otherwise
-- carry an empty type and be neither one thing nor the other.
UPDATE questions SET type = 'MCQ' WHERE type IS NULL OR type = '';

-- ── Test cases ──────────────────────────────────────────────────────────────
--
-- The sample/hidden distinction is the whole point of a coding round: samples
-- are shown and runnable, hidden ones are the marking. `weight` lets an edge
-- case count for more than a happy path.

CREATE TABLE IF NOT EXISTS test_cases (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    question_id     BIGINT       NOT NULL,
    input           TEXT         NULL,
    expected_output TEXT         NULL,
    sample          BIT(1)       NOT NULL DEFAULT b'0',
    weight          DOUBLE       NULL DEFAULT 1.0,
    label           VARCHAR(255) NULL,
    display_order   INT          NULL,
    PRIMARY KEY (id),
    KEY idx_test_case_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── Answers ─────────────────────────────────────────────────────────────────
--
-- A coding answer keeps the program itself, not just whether it was right:
-- an appeal against a mark is unanswerable without the code that earned it.
-- tests_passed and awarded_marks are what make partial credit possible.

ALTER TABLE answers
    ADD COLUMN source_code   TEXT         NULL,
    ADD COLUMN language      VARCHAR(32)  NULL,
    ADD COLUMN tests_passed  INT          NULL,
    ADD COLUMN tests_total   INT          NULL,
    ADD COLUMN awarded_marks DOUBLE       NULL,
    ADD COLUMN judge_message TEXT         NULL;

-- ============================================================================
--  VERIFY
--      SHOW COLUMNS FROM questions LIKE 'type';
--      SHOW COLUMNS FROM questions LIKE 'question_text';   -- expect text
--      SHOW COLUMNS FROM answers   LIKE 'source_code';
--      SHOW CREATE TABLE test_cases;
--      SELECT type, COUNT(*) FROM questions GROUP BY type; -- expect all MCQ
--
--  AFTERWARDS
--      Coding questions stay unavailable until a judge is configured. See
--      deploy/docker-compose.judge.yml, then set judge.provider=judge0.
--      Leaving judge.provider unset is safe: the feature simply stays off.
-- ============================================================================
