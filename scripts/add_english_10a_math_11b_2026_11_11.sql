\set ON_ERROR_STOP on
\encoding UTF8
BEGIN;
SELECT id FROM schedule_school WHERE id=1 FOR UPDATE;

CREATE TEMP TABLE requested_lessons ON COMMIT DROP AS
SELECT 1::bigint AS school_id,p.class_id,p.subject_id,u.teacher_id,p.room_id,
       DATE '2026-11-11' AS lesson_date,p.start_time::time AS start_time,p.end_time::time AS end_time,
       md5('requested-lessons-2026-11-11:' || p.class_id || ':' || p.subject_id) AS request_token
FROM (VALUES
    (1,5,'teacher_eng', 2,'10:40','11:25'),
    (2,1,'teacher_math',1,'09:40','10:25')
) AS p(class_id,subject_id,login,room_id,start_time,end_time)
JOIN users u ON u.login=p.login AND u.role='TEACHER' AND u.enabled AND u.school_id=1
WHERE NOT EXISTS (SELECT 1 FROM schedule_lesson l WHERE l.school_id=1 AND l.class_id=p.class_id
    AND l.subject_id=p.subject_id AND l.lesson_date=DATE '2026-11-11');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM requested_lessons p WHERE NOT EXISTS (
        SELECT 1 FROM teaching_assignment a WHERE a.school_id=p.school_id AND a.class_id=p.class_id
          AND a.subject_id=p.subject_id AND a.teacher_id=p.teacher_id)) THEN
        RAISE EXCEPTION 'Missing teacher/class/subject assignment';
    END IF;
    IF EXISTS (SELECT 1 FROM requested_lessons p JOIN schedule_class c ON c.id=p.class_id
        LEFT JOIN schedule_room r ON r.id=p.room_id AND r.school_id=p.school_id
        WHERE r.id IS NULL OR r.capacity<greatest(c.student_count,
          (SELECT count(*) FROM users u WHERE u.student_class_id=c.id AND u.school_id=c.school_id AND u.role='STUDENT'))) THEN
        RAISE EXCEPTION 'Room capacity is insufficient';
    END IF;
    IF EXISTS (SELECT 1 FROM requested_lessons p
        WHERE p.lesson_date+p.start_time <= current_timestamp AT TIME ZONE 'Asia/Bishkek') THEN
        RAISE EXCEPTION 'Cannot add a lesson that has already started';
    END IF;
    IF EXISTS (SELECT 1 FROM requested_lessons p JOIN schedule_lesson l
        ON l.school_id=p.school_id AND l.lesson_date=p.lesson_date
          AND l.start_time<p.end_time AND p.start_time<l.end_time
        WHERE l.class_id=p.class_id OR l.teacher_id=p.teacher_id OR l.room_id=p.room_id) THEN
        RAISE EXCEPTION 'Class, teacher or room time conflict';
    END IF;
END $$;

INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
SELECT school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token
FROM requested_lessons;

SELECT l.id,c.name AS class,s.name AS subject,u.login AS teacher,r.name AS room,l.start_time,l.end_time
FROM schedule_lesson l JOIN schedule_class c ON c.id=l.class_id
JOIN schedule_subject s ON s.id=l.subject_id
JOIN users u ON u.teacher_id=l.teacher_id AND u.school_id=l.school_id
JOIN schedule_room r ON r.id=l.room_id
WHERE l.school_id=1 AND l.lesson_date=DATE '2026-11-11'
  AND ((l.class_id=1 AND l.subject_id=5) OR (l.class_id=2 AND l.subject_id=1))
ORDER BY l.class_id;
COMMIT;
