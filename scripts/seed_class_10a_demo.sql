-- Manual demonstration data; does not change application rules or existing marks.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT pg_advisory_xact_lock(801001);
CREATE TEMP TABLE demo_students ON COMMIT DROP AS
 SELECT id,login,row_number() OVER(ORDER BY id)::int AS num FROM users
 WHERE school_id=1 AND student_class_id=1 AND role='STUDENT';
CREATE TEMP TABLE demo_lessons ON COMMIT DROP AS
 SELECT l.id,l.subject_id,l.teacher_id,l.lesson_date,l.start_time FROM schedule_lesson l WHERE false;
CREATE TEMP TABLE demo_homework(id bigint) ON COMMIT DROP;
DO $$
DECLARE
 monday date := date_trunc('week',current_date)::date;
 day date; off int; target int; lesson bigint; room bigint; slot time;
 assignment record; pupil record; entry record; quarter bigint; actor bigint;
 hw bigint; n int; chosen_subject bigint;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schedule_class WHERE school_id=1 AND id=1 AND name='10-А')
 OR NOT EXISTS(SELECT 1 FROM demo_students) THEN RAISE EXCEPTION 'Class or students missing'; END IF;
 FOR off IN 0..11 LOOP
  day := monday-14+off;
  IF extract(isodow FROM day)>5 THEN CONTINUE; END IF;
  target := CASE WHEN off%2=0 THEN 4 ELSE 3 END;
  FOR assignment IN
   SELECT DISTINCT ON(ta.subject_id) ta.subject_id,ta.teacher_id,u.id AS actor
   FROM teaching_assignment ta JOIN users u ON u.school_id=ta.school_id AND u.teacher_id=ta.teacher_id AND u.role='TEACHER'
   WHERE ta.school_id=1 AND ta.class_id=1 ORDER BY ta.subject_id,u.id
  LOOP
   EXIT WHEN (SELECT count(DISTINCT subject_id) FROM schedule_lesson WHERE school_id=1 AND class_id=1 AND lesson_date=day AND subgroup_id IS NULL)>=target;
   IF EXISTS(SELECT 1 FROM schedule_lesson WHERE school_id=1 AND class_id=1 AND lesson_date=day AND subject_id=assignment.subject_id AND subgroup_id IS NULL) THEN CONTINUE; END IF;
   SELECT r.id,t::time INTO room,slot FROM schedule_room r
    CROSS JOIN generate_series(day+time '08:00',day+time '18:00',interval '45 minutes') t
    WHERE r.school_id=1 AND r.capacity >= (SELECT count(*) FROM demo_students)
     AND NOT EXISTS(SELECT 1 FROM schedule_lesson l WHERE l.school_id=1 AND l.lesson_date=day
      AND (l.class_id=1 OR l.teacher_id=assignment.teacher_id OR l.room_id=r.id)
      AND l.start_time<t::time+interval '40 minutes' AND l.end_time>t::time)
    ORDER BY t,r.id LIMIT 1;
   IF slot IS NULL THEN RAISE EXCEPTION 'No free slot on %',day; END IF;
   INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
    VALUES(1,1,assignment.subject_id,assignment.teacher_id,room,day,slot,slot+interval '40 minutes',
     'demo80-10a-'||day||'-'||assignment.subject_id);
  END LOOP;
  IF (SELECT count(DISTINCT subject_id) FROM schedule_lesson WHERE school_id=1 AND class_id=1 AND lesson_date=day AND subgroup_id IS NULL)<3 THEN
   RAISE EXCEPTION 'Not enough subjects on %',day;
  END IF;
 END LOOP;
 INSERT INTO demo_lessons SELECT l.id,l.subject_id,l.teacher_id,l.lesson_date,l.start_time FROM schedule_lesson l
  WHERE l.school_id=1 AND l.class_id=1 AND l.subgroup_id IS NULL AND l.lesson_date>=monday-14 AND l.lesson_date<monday
   AND extract(isodow FROM l.lesson_date)<=5;
 SELECT subject_id INTO STRICT chosen_subject FROM demo_lessons GROUP BY subject_id HAVING count(*)>=3 ORDER BY count(*) DESC,subject_id LIMIT 1;
 FOR pupil IN SELECT * FROM demo_students ORDER BY num LOOP
  n := 0;
  FOR entry IN
   WITH main AS(SELECT * FROM demo_lessons WHERE subject_id=chosen_subject ORDER BY lesson_date,start_time,id LIMIT 3),
   others AS(SELECT DISTINCT ON(subject_id) * FROM demo_lessons WHERE subject_id<>chosen_subject ORDER BY subject_id,lesson_date,start_time,id LIMIT 2)
   SELECT * FROM main UNION ALL SELECT * FROM others
  LOOP
   n := n+1;
   SELECT q.id INTO STRICT quarter FROM academic_quarter q JOIN academic_year y ON y.id=q.year_id
    WHERE y.school_id=1 AND entry.lesson_date BETWEEN q.starts_on AND q.ends_on;
   SELECT id INTO STRICT actor FROM users WHERE school_id=1 AND teacher_id=entry.teacher_id AND role='TEACHER';
   INSERT INTO journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value,saved_at)
    VALUES(entry.id,pupil.id,actor,quarter,
     CASE WHEN pupil.num%4=0 THEN 2 WHEN pupil.num%4=1 THEN 5 WHEN pupil.num%4=2 THEN 3+n%2 ELSE 4+n%2 END,
     entry.lesson_date+time '19:00') ON CONFLICT(lesson_id,student_id) DO NOTHING;
  END LOOP;
  IF n<>5 THEN RAISE EXCEPTION 'Expected five grade opportunities'; END IF;
  INSERT INTO attendance(lesson_id,student_id,status,reason,version)
   SELECT id,pupil.id,
    CASE WHEN pupil.num%3=0 AND row_number() OVER(ORDER BY lesson_date,start_time,id)%7=0 THEN 'ABSENT'
         WHEN pupil.num%3=1 AND row_number() OVER(ORDER BY lesson_date,start_time,id)%6=0 THEN 'LATE' ELSE 'PRESENT' END,
    'Демонстрационные данные 10-А',0 FROM demo_lessons
   ON CONFLICT(lesson_id,student_id) DO NOTHING;
 END LOOP;
 FOR n IN 1..3 LOOP
  SELECT l.*,u.id AS actor INTO STRICT entry FROM demo_lessons l
   JOIN users u ON u.school_id=1 AND u.teacher_id=l.teacher_id AND u.role='TEACHER'
   ORDER BY l.lesson_date,l.start_time,l.id OFFSET (n-1)*5 LIMIT 1;
  INSERT INTO homework(school_id,teacher_id,class_id,subject_id,lesson_id,title,body,due_date,mode,published_at,request_token)
   VALUES(1,entry.teacher_id,1,entry.subject_id,entry.id,
    CASE n WHEN 1 THEN 'Закрепление пройденной темы' WHEN 2 THEN 'Практическая работа' ELSE 'Повторение и самопроверка' END,
    'Выполните задания по теме урока. Запишите ход решения и проверьте ответы.',entry.lesson_date+2,'ONLINE',entry.lesson_date+time '13:00',
    'demo80-10a-hw-'||monday||'-'||n)
   ON CONFLICT(request_token) DO UPDATE SET request_token=excluded.request_token RETURNING id INTO hw;
  INSERT INTO demo_homework VALUES(hw);
  INSERT INTO homework_audience SELECT hw,id FROM demo_students ON CONFLICT DO NOTHING;
  INSERT INTO homework_submission(homework_id,student_id,version,text_answer,status,teacher_comment,submitted_at,checked_at,checked_by)
   SELECT hw,id,1,'Решение заданий с пояснениями.',
    CASE (num+n)%4 WHEN 0 THEN 'ACCEPTED' WHEN 1 THEN 'RETURNED' ELSE 'SENT' END,
    CASE (num+n)%4 WHEN 0 THEN 'Верно, работа принята.' WHEN 1 THEN 'Проверьте вычисления и дополните объяснение.' ELSE NULL END,
    entry.lesson_date+1+time '15:00',
    CASE WHEN (num+n)%4 IN(0,1) THEN entry.lesson_date+2+time '16:00' END,
    CASE WHEN (num+n)%4 IN(0,1) THEN entry.actor END
   FROM demo_students WHERE (num+n)%4<>3 ON CONFLICT(homework_id,student_id,version) DO NOTHING;
 END LOOP;
 IF EXISTS(SELECT 1 FROM demo_students s WHERE
   (SELECT count(*) FROM journal_grade g JOIN demo_lessons l ON l.id=g.lesson_id WHERE g.student_id=s.id)<3)
 THEN RAISE EXCEPTION 'Grade coverage check failed'; END IF;
 IF (SELECT count(*) FROM attendance a JOIN demo_students s ON s.id=a.student_id JOIN demo_lessons l ON l.id=a.lesson_id)
  <> (SELECT count(*) FROM demo_students)*(SELECT count(*) FROM demo_lessons)
 THEN RAISE EXCEPTION 'Attendance coverage check failed'; END IF;
END $$;
SELECT lesson_date,count(*) AS lessons,count(DISTINCT subject_id) AS subjects FROM demo_lessons GROUP BY lesson_date ORDER BY lesson_date;
SELECT s.login,count(g.id) AS grades FROM demo_students s JOIN journal_grade g ON g.student_id=s.id
 JOIN demo_lessons l ON l.id=g.lesson_id GROUP BY s.id,s.login ORDER BY s.id;
SELECT a.status,count(*) FROM attendance a JOIN demo_students s ON s.id=a.student_id JOIN demo_lessons l ON l.id=a.lesson_id GROUP BY a.status;
SELECT coalesce(hs.status,'NOT_SUBMITTED') AS status,count(*) FROM demo_homework h
 JOIN homework_audience a ON a.homework_id=h.id LEFT JOIN homework_submission hs ON hs.homework_id=h.id AND hs.student_id=a.student_id
 GROUP BY hs.status;
COMMIT;
