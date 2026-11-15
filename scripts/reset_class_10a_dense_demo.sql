-- Explicit reset of marks, attendance and online submissions for current 10-А students.
-- Four completed calendar weeks, Monday/Wednesday/Friday, four subjects.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT pg_advisory_xact_lock(801001);
CREATE TEMP TABLE dense_students ON COMMIT DROP AS
 SELECT id,login,row_number() OVER(ORDER BY id)::int AS num,
  ((row_number() OVER(ORDER BY id)-1)%3+1)::int AS cohort
 FROM users WHERE school_id=1 AND student_class_id=1 AND role='STUDENT';
CREATE TEMP TABLE dense_lessons(id bigint PRIMARY KEY,week_no int,ordinal int,subject_id bigint) ON COMMIT DROP;
CREATE TEMP TABLE dense_homework(id bigint PRIMARY KEY) ON COMMIT DROP;
DO $$
DECLARE
 monday date:=date_trunc('week',current_date)::date;
 assignment record; pupil record; entry record;
 w int; n int; h int; day date; lesson bigint; room bigint; slot time; quarter bigint;
 hw bigint; actor bigint; token text; mark int;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schedule_class WHERE id=1 AND school_id=1 AND name='10-А')
 OR (SELECT count(*) FROM dense_students)<3 THEN RAISE EXCEPTION 'Expected class 10-А and at least three students'; END IF;
 IF (SELECT count(DISTINCT s.id) FROM schedule_subject s JOIN teaching_assignment ta ON ta.subject_id=s.id AND ta.school_id=s.school_id
  JOIN users u ON u.teacher_id=ta.teacher_id AND u.school_id=ta.school_id AND u.role='TEACHER'
  WHERE s.school_id=1 AND ta.class_id=1 AND s.name IN('Английский язык','Математика','Русский язык','Физика'))<>4
 THEN RAISE EXCEPTION 'Missing subjects or teachers'; END IF;
 DELETE FROM attendance_notification WHERE student_id IN(SELECT id FROM dense_students);
 DELETE FROM attendance_history WHERE student_id IN(SELECT id FROM dense_students);
 DELETE FROM attendance WHERE student_id IN(SELECT id FROM dense_students);
 ALTER TABLE journal_grade DISABLE TRIGGER journal_grade_immutable;
 DELETE FROM journal_grade WHERE student_id IN(SELECT id FROM dense_students);
 ALTER TABLE journal_grade ENABLE TRIGGER journal_grade_immutable;
 -- Attached files are removed by the submission foreign key's ON DELETE CASCADE.
 DELETE FROM homework_submission WHERE student_id IN(SELECT id FROM dense_students);
 FOR w IN 1..4 LOOP
  FOR n IN 1..3 LOOP
   day:=monday-28+(w-1)*7+(n-1)*2;
   FOR assignment IN
    SELECT DISTINCT ON(s.id) s.id AS subject_id,ta.teacher_id,u.id AS actor
    FROM schedule_subject s JOIN teaching_assignment ta ON ta.subject_id=s.id AND ta.school_id=s.school_id
     JOIN users u ON u.teacher_id=ta.teacher_id AND u.school_id=ta.school_id AND u.role='TEACHER'
    WHERE s.school_id=1 AND ta.class_id=1 AND s.name IN('Английский язык','Математика','Русский язык','Физика')
    ORDER BY s.id,u.id
   LOOP
    token:='dense80-10a-'||day||'-'||assignment.subject_id;
    SELECT id INTO lesson FROM schedule_lesson WHERE request_token=token;
    IF lesson IS NULL THEN
     SELECT id INTO lesson FROM schedule_lesson WHERE school_id=1 AND class_id=1 AND subgroup_id IS NULL
      AND subject_id=assignment.subject_id AND teacher_id=assignment.teacher_id AND lesson_date=day
      ORDER BY start_time,id LIMIT 1;
    END IF;
    IF lesson IS NULL THEN
     SELECT r.id,t::time INTO room,slot FROM schedule_room r
      CROSS JOIN generate_series(day+time '08:00',day+time '19:00',interval '45 minutes') t
      WHERE r.school_id=1 AND r.capacity>=(SELECT count(*) FROM dense_students)
       AND NOT EXISTS(SELECT 1 FROM schedule_lesson l WHERE l.school_id=1 AND l.lesson_date=day
        AND (l.class_id=1 OR l.teacher_id=assignment.teacher_id OR l.room_id=r.id)
        AND l.start_time<t::time+interval '40 minutes' AND l.end_time>t::time)
      ORDER BY t,r.id LIMIT 1;
     IF slot IS NULL THEN RAISE EXCEPTION 'No free slot on %',day; END IF;
     INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
      VALUES(1,1,assignment.subject_id,assignment.teacher_id,room,day,slot,slot+interval '40 minutes',token)
      RETURNING id INTO lesson;
    END IF;
    INSERT INTO dense_lessons VALUES(lesson,w,n,assignment.subject_id);
    SELECT q.id INTO STRICT quarter FROM academic_quarter q JOIN academic_year y ON y.id=q.year_id
     WHERE y.school_id=1 AND day BETWEEN q.starts_on AND q.ends_on;
    FOR pupil IN SELECT * FROM dense_students LOOP
     mark:=CASE pupil.cohort
      WHEN 1 THEN CASE WHEN w=4 THEN 5 ELSE 4 END
      WHEN 2 THEN CASE WHEN (n+pupil.num)%3=0 THEN 3 ELSE 4 END
      ELSE CASE WHEN n<=4-w THEN 3 ELSE 2 END END;
     INSERT INTO journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value,saved_at)
      VALUES(lesson,pupil.id,assignment.actor,quarter,mark,day+time '20:00');
    END LOOP;
   END LOOP;
  END LOOP;
 END LOOP;
 INSERT INTO attendance(lesson_id,student_id,status,reason,version)
  SELECT l.id,s.id,CASE WHEN (l.seq+s.num)%48 IN(3,21) THEN 'ABSENT'
   WHEN (l.seq+s.num)%48=37 THEN 'LATE' ELSE 'PRESENT' END,
   'Демонстрация аналитики 10-А',0
  FROM (SELECT d.id,row_number() OVER(ORDER BY sl.lesson_date,sl.start_time,d.id)::int AS seq
   FROM dense_lessons d JOIN schedule_lesson sl ON sl.id=d.id) l CROSS JOIN dense_students s;
 FOR h IN 1..2 LOOP
  SELECT sl.*,u.id AS actor INTO STRICT entry FROM dense_lessons d JOIN schedule_lesson sl ON sl.id=d.id
   JOIN users u ON u.school_id=1 AND u.teacher_id=sl.teacher_id AND u.role='TEACHER'
   WHERE d.week_no=h+1 AND d.ordinal=1 ORDER BY sl.subject_id OFFSET h-1 LIMIT 1;
  INSERT INTO homework(school_id,teacher_id,class_id,subject_id,lesson_id,title,body,due_date,mode,published_at,request_token)
   VALUES(1,entry.teacher_id,1,entry.subject_id,entry.id,
    CASE h WHEN 1 THEN 'Практика по пройденной теме' ELSE 'Закрепление материала' END,
    'Выполните упражнения по теме урока, запишите решение и проверьте результат.',entry.lesson_date+3,
    'ONLINE',entry.lesson_date+time '14:00','dense80-10a-hw-'||monday||'-'||h)
   ON CONFLICT(request_token) DO UPDATE SET request_token=excluded.request_token RETURNING id INTO hw;
  INSERT INTO dense_homework VALUES(hw);
  INSERT INTO homework_audience SELECT hw,id FROM dense_students ON CONFLICT DO NOTHING;
  INSERT INTO homework_submission(homework_id,student_id,version,text_answer,status,teacher_comment,submitted_at,checked_at,checked_by)
   SELECT hw,id,1,'Решение упражнений с пояснениями.',
    CASE (num+h)%4 WHEN 0 THEN 'ACCEPTED' WHEN 1 THEN 'RETURNED' ELSE 'SENT' END,
    CASE (num+h)%4 WHEN 0 THEN 'Все верно, работа принята.' WHEN 1 THEN 'Исправьте вычисления и поясните ответ.' END,
    entry.lesson_date+1+time '15:00',
    CASE WHEN (num+h)%4 IN(0,1) THEN entry.lesson_date+2+time '16:00' END,
    CASE WHEN (num+h)%4 IN(0,1) THEN entry.actor END
   FROM dense_students WHERE (num+h)%4<>3;
 END LOOP;
 IF (SELECT count(*) FROM dense_lessons)<>48 OR EXISTS(
  SELECT 1 FROM dense_lessons GROUP BY week_no,subject_id HAVING count(*)<>3)
 THEN RAISE EXCEPTION 'Expected 48 lessons, three per subject per week'; END IF;
 IF EXISTS(SELECT 1 FROM dense_students s WHERE
  (SELECT count(*) FROM journal_grade WHERE student_id=s.id)<>48 OR
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='ABSENT')<>2 OR
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='LATE')<>1 OR
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='PRESENT')<>45)
 THEN RAISE EXCEPTION 'Student coverage verification failed'; END IF;
END $$;
SELECT min(sl.lesson_date) AS first_day,max(sl.lesson_date) AS last_day,count(*) AS lessons
 FROM dense_lessons d JOIN schedule_lesson sl ON sl.id=d.id;
SELECT s.login,s.cohort,count(g.id) AS grades FROM dense_students s JOIN journal_grade g ON g.student_id=s.id GROUP BY s.id,s.login,s.cohort ORDER BY s.id;
SELECT a.status,count(*) FROM attendance a JOIN dense_students s ON s.id=a.student_id GROUP BY a.status;
SELECT coalesce(hs.status,'NOT_SUBMITTED') AS status,count(*) FROM dense_homework h
 JOIN homework_audience a ON a.homework_id=h.id LEFT JOIN homework_submission hs ON hs.homework_id=h.id AND hs.student_id=a.student_id GROUP BY hs.status;
COMMIT;
