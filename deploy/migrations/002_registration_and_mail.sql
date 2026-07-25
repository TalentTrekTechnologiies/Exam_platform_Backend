-- ============================================================================
--  Candidate self-registration + email delivery
-- ============================================================================
--  WHY
--  Two capabilities are added:
--
--    1. A public sign-up form per exam, open for a fixed window. Candidates
--       register themselves; staff approve the list; approval turns a submission
--       into a hall ticket. The APPROVAL step is where duplicates and junk get
--       caught before they become credentials.
--
--    2. An email outbox. Hall tickets and exam links are never sent from a
--       request thread — at 2,000+ candidates that would time out mid-send with
--       no record of who was reached. Every message becomes a row, drained by a
--       paced worker with retry, so "who hasn't received theirs?" is a query.
--
--  On a FRESH install these tables are created by ddl-auto=update on first boot,
--  and this file is not needed. Run it only to upgrade a database already on
--  DDL_AUTO=validate, with the application STOPPED.
-- ============================================================================

-- 1. Candidates gained an email + phone. Nullable: roster-uploaded candidates
--    often have neither, and only self-registered ones are guaranteed an email.
ALTER TABLE students
    ADD COLUMN email VARCHAR(320) NULL AFTER name,
    ADD COLUMN phone VARCHAR(20)  NULL AFTER email;

-- 2. One public registration form per exam.
CREATE TABLE registration_forms (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    exam_id           BIGINT       NOT NULL,
    admin_id          BIGINT       NOT NULL,
    token             VARCHAR(64)  NOT NULL,
    opens_at          DATETIME     NOT NULL,
    closes_at         DATETIME     NOT NULL,
    closed_early      BOOLEAN      NOT NULL DEFAULT FALSE,
    instructions      VARCHAR(2000) NULL,
    email_domain      VARCHAR(255) NULL,
    max_registrations INT          NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_regform_token UNIQUE (token),
    -- One live form per exam: two would split the roster with no authoritative half.
    CONSTRAINT uk_regform_exam  UNIQUE (exam_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Submissions. Deliberately NOT students — an unverified claim until approved.
CREATE TABLE registrations (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    form_id      BIGINT       NOT NULL,
    exam_id      BIGINT       NOT NULL,
    admin_id     BIGINT       NOT NULL,
    name         VARCHAR(255) NOT NULL,
    email        VARCHAR(320) NOT NULL,
    phone        VARCHAR(20)  NULL,
    roll_number  VARCHAR(64)  NULL,
    branch       VARCHAR(120) NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    student_id   BIGINT       NULL,
    hall_ticket  VARCHAR(64)  NULL,
    submitted_at DATETIME     NOT NULL,
    reviewed_at  DATETIME     NULL,
    review_note  VARCHAR(500) NULL,
    PRIMARY KEY (id),
    -- One registration per email per form; a double-submit updates in place.
    CONSTRAINT uk_registration_form_email UNIQUE (form_id, email),
    KEY idx_registration_form_status (form_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. The mail outbox.
CREATE TABLE outbound_emails (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id        BIGINT       NOT NULL,
    exam_id         BIGINT       NULL,
    student_id      BIGINT       NULL,
    to_address      VARCHAR(320) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    body_html       LONGTEXT     NOT NULL,
    body_text       LONGTEXT     NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME     NOT NULL,
    last_error      VARCHAR(1000) NULL,
    -- Which app instance is sending this. The exam-day stack runs three, so
    -- without a claim all three would drain the same rows and send duplicates.
    claimed_by      VARCHAR(64)  NULL,
    claimed_at      DATETIME     NULL,
    created_at      DATETIME     NOT NULL,
    sent_at         DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_next (status, next_attempt_at),
    KEY idx_outbox_exam (exam_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
--  VERIFY
--      SHOW TABLES LIKE 'registration%';   -- registration_forms, registrations
--      SHOW TABLES LIKE 'outbound_emails';
--      SHOW COLUMNS FROM students LIKE 'email';
-- ============================================================================
