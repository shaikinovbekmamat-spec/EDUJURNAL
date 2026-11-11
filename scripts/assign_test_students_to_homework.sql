\set ON_ERROR_STOP on
\encoding UTF8

-- Assign the existing 10-А test homework to pupils added after its publication.
-- Does not change submissions or automatically reassign historical homework.
BEGIN;
SELECT id FROM schedule_school WHERE id=1 FOR UPDATE;
SELECT id FROM homework WHERE id=1 AND school_id=1 AND class_id=1 FOR UPDATE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM homework WHERE id=1 AND school_id=1 AND class_id=1) THEN
        RAISE EXCEPTION 'Expected homework 1 for class 10-А (1), school 1';
    END IF;
    IF (SELECT count(*) FROM users WHERE school_id=1 AND student_class_id=1 AND role='STUDENT'
        AND login IN ('stud6','stud7','stud8','stud9','stud10')) <> 5 THEN
        RAISE EXCEPTION 'Expected five new test students stud6–stud10 in class 10-А';
    END IF;
END $$;

INSERT INTO homework_audience(homework_id,student_id)
SELECT h.id,u.id FROM homework h
JOIN users u ON u.school_id=h.school_id AND u.student_class_id=h.class_id AND u.role='STUDENT'
WHERE h.id=1 AND h.school_id=1 AND h.class_id=1
  AND u.login IN ('stud6','stud7','stud8','stud9','stud10')
ON CONFLICT (homework_id,student_id) DO NOTHING
RETURNING homework_id,student_id;

SELECT u.login,u.full_name FROM homework_audience a JOIN users u ON u.id=a.student_id
WHERE a.homework_id=1 ORDER BY u.id;
COMMIT;
