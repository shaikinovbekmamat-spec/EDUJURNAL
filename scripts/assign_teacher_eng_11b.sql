\set ON_ERROR_STOP on
\encoding UTF8

BEGIN;
SELECT id FROM schedule_school WHERE id=1 FOR UPDATE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM users u
        JOIN schedule_teacher t ON t.id=u.teacher_id AND t.school_id=u.school_id
        JOIN schedule_class c ON c.school_id=u.school_id AND c.id=2 AND c.name='11-Б'
        JOIN schedule_subject s ON s.school_id=u.school_id AND s.id=5 AND s.name='Английский язык'
        WHERE u.login='teacher_eng' AND u.role='TEACHER' AND u.enabled AND u.school_id=1
    ) THEN
        RAISE EXCEPTION 'Expected active teacher_eng, class 11-Б (2), and English (5) in school 1';
    END IF;
END $$;

INSERT INTO schedule_teacher_subject(school_id,teacher_id,subject_id)
SELECT school_id,teacher_id,5 FROM users WHERE login='teacher_eng' AND school_id=1
ON CONFLICT (school_id,teacher_id,subject_id) DO NOTHING;

INSERT INTO teaching_assignment(school_id,class_id,subject_id,teacher_id)
SELECT school_id,2,5,teacher_id FROM users WHERE login='teacher_eng' AND school_id=1
ON CONFLICT (school_id,class_id,subject_id,teacher_id) DO NOTHING;

-- The same assignments are used by HomeworkService.choices and its access checks.
SELECT c.name AS class,s.name AS subject,u.login AS teacher_login
FROM teaching_assignment a
JOIN users u ON u.teacher_id=a.teacher_id AND u.school_id=a.school_id
JOIN schedule_class c ON c.id=a.class_id AND c.school_id=a.school_id
JOIN schedule_subject s ON s.id=a.subject_id AND s.school_id=a.school_id
WHERE u.login='teacher_eng'
ORDER BY c.name,s.name;
COMMIT;
