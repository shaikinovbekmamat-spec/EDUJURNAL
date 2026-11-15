\set ON_ERROR_STOP on
\encoding UTF8

-- Test lessons for 11 November 2026, school 1. Safe to run again.
BEGIN;
SELECT id FROM schedule_school WHERE id = 1 FOR UPDATE;

CREATE TEMP TABLE seed_lessons (
    school_id bigint, class_id bigint, subject_id bigint, teacher_id bigint, room_id bigint,
    lesson_date date, lesson_number integer, start_time time, end_time time, request_token varchar(36)
) ON COMMIT DROP;

INSERT INTO seed_lessons
SELECT 1, class_id, subject_id, teacher_id, room_id, DATE '2026-11-11', lesson_number,
       start_time::time, end_time::time,
       md5('seed-schedule-2026-11-11:' || class_id || ':' || lesson_number)
FROM (VALUES
    (1, 1, 10, 1, 1, '08:00', '08:45'), -- 10-А: mathematics, room 101
    (1, 2, 11, 2, 2, '08:50', '09:35'), -- 10-А: Russian, room 102
    (1, 3, 12, 2, 3, '09:40', '10:25'), -- 10-А: theoretical physics, 25-seat room
    (2, 2, 11, 2, 1, '08:00', '08:45'), -- 11-Б: Russian, room 102
    (2, 3, 12, 3, 2, '08:50', '09:35'), -- 11-Б: physics, laboratory (20 seats)
    (2, 1, 10, 1, 3, '09:40', '10:25')  -- 11-Б: mathematics, room 101
) AS plan(class_id, subject_id, teacher_id, room_id, lesson_number, start_time, end_time);

DO $$
BEGIN
    IF (SELECT count(*) FROM schedule_class WHERE school_id=1 AND
            ((id=1 AND name='10-А') OR (id=2 AND name='11-Б'))) <> 2 THEN
        RAISE EXCEPTION 'Expected classes 10-А (1) and 11-Б (2) in school 1';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p
        LEFT JOIN teaching_assignment a ON a.school_id=p.school_id AND a.class_id=p.class_id
            AND a.subject_id=p.subject_id AND a.teacher_id=p.teacher_id
        LEFT JOIN schedule_teacher_subject ts ON ts.school_id=p.school_id
            AND ts.teacher_id=p.teacher_id AND ts.subject_id=p.subject_id
        WHERE a.teacher_id IS NULL OR ts.teacher_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Missing teacher/class/subject assignment';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p
        JOIN schedule_class c ON c.id=p.class_id AND c.school_id=p.school_id
        LEFT JOIN schedule_room r ON r.id=p.room_id AND r.school_id=p.school_id
        WHERE r.id IS NULL OR r.capacity < greatest(c.student_count,
            (SELECT count(*) FROM users u WHERE u.student_class_id=c.id AND u.school_id=c.school_id AND u.role='STUDENT'))
    ) THEN
        RAISE EXCEPTION 'Missing room or insufficient capacity';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p JOIN schedule_teacher_unavailable u
        ON u.school_id=p.school_id AND u.teacher_id=p.teacher_id
            AND u.weekday=extract(isodow FROM p.lesson_date) AND u.lesson_number=p.lesson_number
    ) THEN
        RAISE EXCEPTION 'A teacher is unavailable';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p JOIN schedule_lesson l ON l.request_token=p.request_token
        WHERE l.school_id<>p.school_id OR l.class_id<>p.class_id OR l.subject_id<>p.subject_id
            OR l.teacher_id<>p.teacher_id OR l.room_id<>p.room_id OR l.subgroup_id IS NOT NULL
            OR l.lesson_date<>p.lesson_date OR l.start_time<>p.start_time OR l.end_time<>p.end_time
    ) THEN
        RAISE EXCEPTION 'A previously seeded lesson has changed; existing lessons will not be overwritten';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p
        WHERE p.lesson_date+p.start_time <= (current_timestamp AT TIME ZONE 'Asia/Bishkek')
          AND NOT EXISTS (SELECT 1 FROM schedule_lesson l WHERE l.request_token=p.request_token)
    ) THEN
        RAISE EXCEPTION 'Cannot add lessons that have already started (Asia/Bishkek)';
    END IF;

    IF EXISTS (
        SELECT 1 FROM seed_lessons p JOIN schedule_lesson l
            ON l.school_id=p.school_id AND l.lesson_date=p.lesson_date
            AND l.start_time<p.end_time AND p.start_time<l.end_time
            AND l.request_token<>p.request_token
        WHERE l.class_id=p.class_id OR l.teacher_id=p.teacher_id OR l.room_id=p.room_id
    ) OR EXISTS (
        SELECT 1 FROM seed_lessons a JOIN seed_lessons b ON a.request_token<b.request_token
            AND a.lesson_date=b.lesson_date AND a.start_time<b.end_time AND b.start_time<a.end_time
        WHERE a.class_id=b.class_id OR a.teacher_id=b.teacher_id OR a.room_id=b.room_id
    ) THEN
        RAISE EXCEPTION 'Class, teacher or room time conflict';
    END IF;
END $$;

INSERT INTO schedule_lesson (school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
SELECT p.school_id,p.class_id,p.subject_id,p.teacher_id,p.room_id,p.lesson_date,p.start_time,p.end_time,p.request_token
FROM seed_lessons p WHERE NOT EXISTS (SELECT 1 FROM schedule_lesson l WHERE l.request_token=p.request_token)
ORDER BY p.class_id,p.start_time;

SELECT l.id,c.name AS class,s.name AS subject,t.name AS teacher,r.name AS room,
       l.lesson_date,l.start_time,l.end_time
FROM schedule_lesson l JOIN seed_lessons p ON p.request_token=l.request_token
JOIN schedule_class c ON c.id=l.class_id JOIN schedule_subject s ON s.id=l.subject_id
JOIN schedule_teacher t ON t.id=l.teacher_id JOIN schedule_room r ON r.id=l.room_id
ORDER BY l.class_id,l.start_time;
COMMIT;
