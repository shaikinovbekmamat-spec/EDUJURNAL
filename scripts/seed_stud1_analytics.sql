-- Manual test fixture, not a production migration. Repeated runs in the same week are safe.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT pg_advisory_xact_lock(801001);
CREATE TEMP TABLE seeded_grades(lesson_id bigint, subject_id bigint, week_no int, grade int) ON COMMIT DROP;
DO $$
DECLARE
 student bigint; teacher bigint; teacher_user bigint; subject bigint; room bigint;
 lesson bigint; quarter bigint; hw bigint; day date; slot time;
 monday date := date_trunc('week', current_date)::date;
 w int; s int; n int; amount int; mark int; attendance_no int := 0; token text;
BEGIN
 SELECT id INTO STRICT student FROM users WHERE login='stud1' AND role='STUDENT' AND school_id=1 AND student_class_id=1;
 IF NOT EXISTS (SELECT 1 FROM schedule_class WHERE id=1 AND school_id=1 AND name='10-А') THEN
   RAISE EXCEPTION 'Expected class 10-А (school 1, class 1)';
 END IF;
 FOR w IN 1..4 LOOP
  FOR s IN 1..2 LOOP
   SELECT id INTO STRICT subject FROM schedule_subject WHERE school_id=1 AND name=CASE s WHEN 1 THEN 'Математика' ELSE 'Физика' END;
   SELECT ta.teacher_id,u.id INTO STRICT teacher,teacher_user FROM teaching_assignment ta
    JOIN users u ON u.school_id=ta.school_id AND u.teacher_id=ta.teacher_id AND u.role='TEACHER'
    WHERE ta.school_id=1 AND ta.class_id=1 AND ta.subject_id=subject ORDER BY u.id LIMIT 1;
   SELECT id INTO STRICT room FROM schedule_room WHERE school_id=1 ORDER BY id LIMIT 1;
   amount := CASE WHEN (s=1 AND w IN (2,3)) OR (s=2 AND w=2) THEN 3 ELSE 2 END;
   FOR n IN 1..amount LOOP
    day := monday - w*7 + CASE n WHEN 2 THEN 3 ELSE 1 END;
    token := 'analytics80-' || day || '-' || subject || '-' || n;
    SELECT id INTO lesson FROM schedule_lesson WHERE request_token=token;
    IF lesson IS NULL THEN
     SELECT l.id INTO lesson FROM schedule_lesson l
      WHERE l.school_id=1 AND l.class_id=1 AND l.subject_id=subject AND l.lesson_date=day
       AND l.subgroup_id IS NULL AND l.teacher_id=teacher
       AND NOT EXISTS (SELECT 1 FROM journal_grade g WHERE g.lesson_id=l.id AND g.student_id=student)
       AND NOT EXISTS (SELECT 1 FROM seeded_grades g WHERE g.lesson_id=l.id)
      ORDER BY l.start_time LIMIT 1;
    END IF;
    IF lesson IS NULL THEN
     SELECT t::time INTO slot FROM generate_series(day+time '08:00',day+time '19:00',interval '45 minutes') t
      WHERE NOT EXISTS (SELECT 1 FROM schedule_lesson l WHERE l.school_id=1 AND l.lesson_date=day
       AND (l.class_id=1 OR l.teacher_id=teacher OR l.room_id=room)
       AND l.start_time < t::time+interval '40 minutes' AND l.end_time > t::time)
      ORDER BY t LIMIT 1;
     IF slot IS NULL THEN RAISE EXCEPTION 'No free lesson slot on %',day; END IF;
     INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
      VALUES(1,1,subject,teacher,room,day,slot,slot+interval '40 minutes',token) RETURNING id INTO lesson;
    END IF;
    mark := CASE WHEN s=1 AND w=3 THEN 3 WHEN s=1 AND w=2 THEN 4 WHEN s=1 AND w=1 THEN 5
                 WHEN s=2 AND w IN (2,3) THEN 2 ELSE NULL END;
    IF mark IS NOT NULL THEN
     SELECT q.id INTO STRICT quarter FROM academic_quarter q JOIN academic_year y ON y.id=q.year_id
      WHERE y.school_id=1 AND day BETWEEN q.starts_on AND q.ends_on;
     INSERT INTO journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value,saved_at)
      VALUES(lesson,student,teacher_user,quarter,mark,day+time '20:00')
      ON CONFLICT(lesson_id,student_id) DO NOTHING;
     INSERT INTO seeded_grades VALUES(lesson,subject,w,mark);
     attendance_no := attendance_no+1;
     IF attendance_no<=7 THEN
      INSERT INTO attendance(lesson_id,student_id,status,reason,version)
       VALUES(lesson,student,CASE WHEN attendance_no<=2 THEN 'ABSENT' ELSE 'PRESENT' END,'Тест аналитики 80%',0)
       ON CONFLICT(lesson_id,student_id) DO NOTHING;
     END IF;
    END IF;
   END LOOP;
  END LOOP;
 END LOOP;
 SELECT id INTO STRICT subject FROM schedule_subject WHERE school_id=1 AND name='Математика';
 SELECT ta.teacher_id,u.id INTO STRICT teacher,teacher_user FROM teaching_assignment ta
  JOIN users u ON u.school_id=ta.school_id AND u.teacher_id=ta.teacher_id
  WHERE ta.school_id=1 AND ta.class_id=1 AND ta.subject_id=subject ORDER BY u.id LIMIT 1;
 FOR n IN 1..3 LOOP
  day := monday-21+n;
  INSERT INTO homework(school_id,teacher_id,class_id,subject_id,title,body,due_date,mode,published_at,request_token)
   VALUES(1,teacher,1,subject,'Тест аналитики: ДЗ '||n,'Решить задачи по математике. Тестовые данные.',day+2,'ONLINE',day+time '09:00','analytics80-hw-'||monday||'-'||n)
   ON CONFLICT(request_token) DO UPDATE SET request_token=excluded.request_token RETURNING id INTO hw;
  INSERT INTO homework_audience VALUES(hw,student) ON CONFLICT DO NOTHING;
  IF n<3 THEN
   INSERT INTO homework_submission(homework_id,student_id,version,text_answer,status,teacher_comment,submitted_at,checked_at,checked_by)
    VALUES(hw,student,1,'Тестовое решение',CASE n WHEN 1 THEN 'ACCEPTED' ELSE 'RETURNED' END,
     CASE n WHEN 1 THEN 'Работа принята' ELSE 'Исправьте решение второй задачи' END,
     day+time '12:00',day+time '14:00',teacher_user) ON CONFLICT(homework_id,student_id,version) DO NOTHING;
  END IF;
 END LOOP;
 INSERT INTO school_notification(recipient_id,event_key,body,link,student_id,created_at)
  VALUES(student,'analytics80-grade-'||monday,'Выставлена оценка 5 по Математике','/analytics',student,monday-3+time '14:00'),
        (student,'analytics80-homework-'||monday,'Работа принята','/homework',student,monday-17+time '14:00')
  ON CONFLICT(recipient_id,event_key) DO NOTHING;
 IF (SELECT count(*) FROM seeded_grades)<>13 OR
    (SELECT count(*) FROM seeded_grades x JOIN journal_grade g ON g.lesson_id=x.lesson_id AND g.student_id=student AND g.grade_value=x.grade)<>13 THEN
  RAISE EXCEPTION 'Grade fixture verification failed';
 END IF;
END $$;
SELECT s.name,x.week_no,array_agg(g.grade_value ORDER BY l.lesson_date,l.start_time) AS grades
 FROM seeded_grades x JOIN schedule_lesson l ON l.id=x.lesson_id JOIN schedule_subject s ON s.id=x.subject_id
 JOIN journal_grade g ON g.lesson_id=x.lesson_id JOIN users u ON u.id=g.student_id AND u.login='stud1'
 GROUP BY s.name,x.week_no ORDER BY s.name,x.week_no DESC;
SELECT a.status,count(*) FROM attendance a JOIN seeded_grades x ON x.lesson_id=a.lesson_id
 JOIN users u ON u.id=a.student_id AND u.login='stud1' GROUP BY a.status;
SELECT h.title,coalesce(hs.status,'NOT_SUBMITTED') AS status FROM homework h
 JOIN homework_audience a ON a.homework_id=h.id JOIN users u ON u.id=a.student_id AND u.login='stud1'
 LEFT JOIN homework_submission hs ON hs.homework_id=h.id AND hs.student_id=u.id
 WHERE h.request_token LIKE 'analytics80-hw-'||date_trunc('week',current_date)::date||'-%' ORDER BY h.title;
SELECT n.body FROM school_notification n JOIN users u ON u.id=n.recipient_id AND u.login='stud1'
 WHERE n.event_key IN ('analytics80-grade-'||date_trunc('week',current_date)::date,'analytics80-homework-'||date_trunc('week',current_date)::date);
COMMIT;
