-- ============================================================================
--  Scope candidates to their institution
-- ============================================================================
--  WHY
--  Hall tickets were unique across the ENTIRE platform. Roll numbers like
--  24CSE001 repeat across colleges constantly, so the second institution to
--  enrol that number silently inherited the first institution's student record.
--  The consequences were real and all bad:
--
--    * College B's roster displayed College A's student name.
--    * College B's actual candidate could not sign in at all.
--    * College A's student was mapped to BOTH colleges' exams.
--
--  A hall ticket is now unique WITHIN an institution, which is the only
--  definition that matches how colleges actually number their students.
--
--  Hibernate's ddl-auto=update can ADD the new column and constraint but will
--  never DROP the old global index, so that step must be run deliberately.
-- ============================================================================

--  Run this with the application STOPPED.

-- 1. Add the owning institution. Nullable first, so existing rows survive.
ALTER TABLE students
    ADD COLUMN admin_id BIGINT NULL AFTER id;

-- 2. Backfill. Every existing candidate is claimed by the institution that owns
--    the exam they were enrolled in.
UPDATE students s
   JOIN (
        SELECT es.student_id, MIN(e.admin_id) AS admin_id
          FROM exam_student es
          JOIN exams e ON e.id = es.exam_id
         WHERE e.admin_id IS NOT NULL
         GROUP BY es.student_id
   ) owner ON owner.student_id = s.id
    SET s.admin_id = owner.admin_id
  WHERE s.admin_id IS NULL;

-- 3. Orphans — candidates never mapped to any exam — go to the first admin, or
--    delete them if you would rather start clean.
--    Inspect first:
--        SELECT * FROM students WHERE admin_id IS NULL;
UPDATE students
   SET admin_id = (SELECT MIN(id) FROM admin)
 WHERE admin_id IS NULL;

-- 4. Drop the platform-wide unique index. THIS is the actual fix.
ALTER TABLE students DROP INDEX hall_ticket;

-- 5. Lock in the new rule.
ALTER TABLE students
    MODIFY COLUMN admin_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_student_institution_ticket UNIQUE (admin_id, hall_ticket);

-- 6. Institution URL slugs, so each college gets its own candidate entrance.
ALTER TABLE admin
    ADD COLUMN code VARCHAR(60) NULL AFTER id;

--    Derive a slug from the name for existing institutions, then make it unique.
UPDATE admin
   SET code = LOWER(REGEXP_REPLACE(TRIM(college_name), '[^a-zA-Z0-9]+', '-'))
 WHERE code IS NULL;

--    Verify none collided before enforcing uniqueness:
--        SELECT code, COUNT(*) FROM admin GROUP BY code HAVING COUNT(*) > 1;
ALTER TABLE admin
    MODIFY COLUMN code VARCHAR(60) NOT NULL,
    ADD CONSTRAINT uk_admin_code UNIQUE (code);

-- ============================================================================
--  VERIFY
-- ============================================================================
--  Two institutions may now both enrol 24CSE001:
--
--      SELECT a.college_name, s.hall_ticket, s.name
--        FROM students s JOIN admin a ON a.id = s.admin_id
--       ORDER BY s.hall_ticket;
--
--  And the old global index must be gone:
--
--      SHOW INDEX FROM students;   -- expect PRIMARY + uk_student_institution_ticket
-- ============================================================================
