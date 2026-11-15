\set ON_ERROR_STOP on
\if :{?school_id}
\else
\set school_id 1
\endif
BEGIN;
SELECT id FROM schedule_school WHERE id=:school_id FOR UPDATE;
\ir ../target/teacher-specializations.sql

INSERT INTO schedule_teacher_subject(school_id,teacher_id,subject_id)
SELECT school_id,teacher_id,subject_id FROM expected_teacher_subject ON CONFLICT DO NOTHING;

-- Repair out-of-specialty assignments in the fictional example, preserving dates and subjects.
DO $$
DECLARE bad record; replacement bigint;
BEGIN
 FOR bad IN
   SELECT DISTINCT l.school_id,l.teacher_id,l.subject_id,coalesce(l.series_id,'lesson:'||l.id) series_key
   FROM schedule_lesson l
   WHERE EXISTS(SELECT 1 FROM expected_teacher_subject x WHERE x.school_id=l.school_id AND x.teacher_id=l.teacher_id)
     AND NOT EXISTS(SELECT 1 FROM expected_teacher_subject x WHERE x.school_id=l.school_id AND x.teacher_id=l.teacher_id AND x.subject_id=l.subject_id)
 LOOP
   SELECT x.teacher_id INTO replacement FROM expected_teacher_subject x
   WHERE x.school_id=bad.school_id AND x.subject_id=bad.subject_id
     AND NOT EXISTS(
       SELECT 1 FROM schedule_lesson original JOIN schedule_lesson occupied
         ON occupied.school_id=original.school_id AND occupied.lesson_date=original.lesson_date
         AND occupied.start_time<original.end_time AND original.start_time<occupied.end_time
         AND occupied.id<>original.id AND occupied.teacher_id=x.teacher_id
       WHERE original.school_id=bad.school_id AND original.teacher_id=bad.teacher_id
         AND original.subject_id=bad.subject_id AND coalesce(original.series_id,'lesson:'||original.id)=bad.series_key)
   ORDER BY x.teacher_id LIMIT 1;
   IF replacement IS NULL THEN RAISE EXCEPTION 'No qualified teacher is available for series %; nothing was changed',bad.series_key; END IF;
   UPDATE schedule_lesson SET teacher_id=replacement,version=version+1
   WHERE school_id=bad.school_id AND teacher_id=bad.teacher_id AND subject_id=bad.subject_id
     AND coalesce(series_id,'lesson:'||id)=bad.series_key;
 END LOOP;
END $$;

DELETE FROM schedule_teacher_subject a
WHERE EXISTS(SELECT 1 FROM expected_teacher_subject x WHERE x.school_id=a.school_id AND x.teacher_id=a.teacher_id)
  AND NOT EXISTS(SELECT 1 FROM expected_teacher_subject x WHERE x.school_id=a.school_id AND x.teacher_id=a.teacher_id AND x.subject_id=a.subject_id);

-- Only the existing fictional example accounts are linked here; real accounts need explicit profiles.
UPDATE users u SET teacher_id=t.id, full_name=t.name FROM schedule_teacher t
WHERE u.login='teacher1' AND u.role='TEACHER' AND u.school_id=:school_id AND t.school_id=u.school_id
  AND t.name='Петров Дмитрий Андреевич' AND u.teacher_id IS NULL;
UPDATE users u SET student_class_id=c.id FROM schedule_class c
WHERE u.login='student1' AND u.role='STUDENT' AND u.school_id=:school_id AND c.school_id=u.school_id
  AND c.name='5А' AND u.student_class_id IS NULL;

SELECT u.login,u.role,t.name AS teacher,c.name AS student_class
FROM users u LEFT JOIN schedule_teacher t ON t.school_id=u.school_id AND t.id=u.teacher_id
LEFT JOIN schedule_class c ON c.school_id=u.school_id AND c.id=u.student_class_id
WHERE u.school_id=:school_id ORDER BY u.id;
COMMIT;
