-- Class ID is authoritative: currently school 1, class 2 is named 11-Б.
-- Existing accounts/passwords are preserved (seven students already enrolled).
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT pg_advisory_xact_lock(801001);
CREATE TEMP TABLE demo2_students ON COMMIT DROP AS
 SELECT id,login,full_name,row_number() OVER(ORDER BY substring(login FROM '[0-9]+$')::int NULLS LAST,id)::int AS profile
 FROM users WHERE school_id=1 AND student_class_id=2 AND role='STUDENT';
CREATE TEMP TABLE demo2_lessons(id bigint PRIMARY KEY,subject_id bigint,week_no int,ordinal int) ON COMMIT DROP;
CREATE TEMP TABLE demo2_expected(student_id bigint,lesson_id bigint,grade int,PRIMARY KEY(student_id,lesson_id)) ON COMMIT DROP;
CREATE TEMP TABLE demo2_profiles(student_id bigint PRIMARY KEY,favorite bigint,weak bigint) ON COMMIT DROP;
CREATE TEMP TABLE demo2_homework(id bigint PRIMARY KEY) ON COMMIT DROP;
DO $$
DECLARE
 pupil record; assignment record; entry record; subject bigint; room bigint; slot time;
 lesson bigint; quarter bigint; actor bigint; favorite bigint; weak bigint; middle_subject bigint;
 day date; w int; n int; h int; hw bigint; marks int[]; value int;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schedule_class WHERE school_id=1 AND id=2) THEN RAISE EXCEPTION 'Class 2 not found'; END IF;
 IF (SELECT count(*) FROM demo2_students)<>7 THEN RAISE EXCEPTION 'Expected seven inspected students; review enrollment before rerunning'; END IF;
 IF (SELECT count(*) FROM schedule_subject WHERE school_id=1 AND name IN('Английский язык','Физика','Математика','Русский язык'))<>4 THEN
  RAISE EXCEPTION 'Four subjects required'; END IF;
 FOR w IN 1..4 LOOP
  FOR n IN 1..3 LOOP
   day:=DATE '2026-10-12'+(w-1)*7+(n-1)*2;
   FOR assignment IN
    SELECT DISTINCT ON(s.id) s.id AS subject_id,ta.teacher_id,u.id AS actor
    FROM schedule_subject s JOIN teaching_assignment ta ON ta.subject_id=s.id AND ta.school_id=s.school_id
    JOIN users u ON u.school_id=ta.school_id AND u.teacher_id=ta.teacher_id AND u.role='TEACHER'
    WHERE s.school_id=1 AND ta.class_id=2 AND s.name IN('Английский язык','Физика','Математика','Русский язык') ORDER BY s.id,u.id
   LOOP
    SELECT id INTO lesson FROM schedule_lesson WHERE school_id=1 AND class_id=2 AND subgroup_id IS NULL
     AND subject_id=assignment.subject_id AND teacher_id=assignment.teacher_id AND lesson_date=day ORDER BY start_time,id LIMIT 1;
    IF lesson IS NULL THEN
     SELECT r.id,t::time INTO room,slot FROM schedule_room r
      CROSS JOIN generate_series(day+time '08:00',day+time '19:00',interval '45 minutes') t
      WHERE r.school_id=1 AND r.capacity>=(SELECT count(*) FROM demo2_students)
       AND NOT EXISTS(SELECT 1 FROM schedule_lesson l WHERE l.school_id=1 AND l.lesson_date=day
        AND (l.class_id=2 OR l.teacher_id=assignment.teacher_id OR l.room_id=r.id)
        AND l.start_time<t::time+interval '40 minutes' AND l.end_time>t::time)
      ORDER BY t,r.id LIMIT 1;
     IF slot IS NULL THEN RAISE EXCEPTION 'No free room/teacher/class slot on %',day; END IF;
     INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
      VALUES(1,2,assignment.subject_id,assignment.teacher_id,room,day,slot,slot+interval '40 minutes',
       'demo80-c2-'||day||'-'||assignment.subject_id) RETURNING id INTO lesson;
    END IF;
    INSERT INTO demo2_lessons VALUES(lesson,assignment.subject_id,w,n);
   END LOOP;
  END LOOP;
 END LOOP;
 IF (SELECT count(*) FROM demo2_lessons)<>48 OR EXISTS(SELECT 1 FROM demo2_lessons GROUP BY subject_id,week_no HAVING count(*)<>3) THEN
  RAISE EXCEPTION 'Expected 48 selected lessons, three per subject per week'; END IF;
 -- Reset only selected-period demo marks and attendance of these seven students.
 ALTER TABLE journal_grade DISABLE TRIGGER journal_grade_immutable;
 DELETE FROM journal_grade g USING schedule_lesson l WHERE g.lesson_id=l.id
  AND g.student_id IN(SELECT id FROM demo2_students) AND l.lesson_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08';
 ALTER TABLE journal_grade ENABLE TRIGGER journal_grade_immutable;
 DELETE FROM attendance_notification WHERE student_id IN(SELECT id FROM demo2_students) AND lesson_id IN(SELECT id FROM demo2_lessons);
 DELETE FROM attendance_history WHERE student_id IN(SELECT id FROM demo2_students) AND lesson_id IN(SELECT id FROM demo2_lessons);
 DELETE FROM attendance WHERE student_id IN(SELECT id FROM demo2_students) AND lesson_id IN(SELECT id FROM demo2_lessons);
 FOR pupil IN SELECT * FROM demo2_students ORDER BY profile LOOP
  -- Favorite/weak subject names are resolved to actual catalog IDs.
  SELECT id INTO STRICT favorite FROM schedule_subject WHERE school_id=1 AND name=
   (ARRAY['Английский язык','Математика','Русский язык','Физика','Английский язык','Математика','Физика'])[pupil.profile];
  SELECT id INTO STRICT weak FROM schedule_subject WHERE school_id=1 AND name=
   (ARRAY['Физика','Русский язык','Математика','Английский язык','Русский язык','Физика','Математика'])[pupil.profile];
  INSERT INTO demo2_profiles VALUES(pupil.id,favorite,weak);
  SELECT min(subject_id) INTO middle_subject FROM demo2_lessons WHERE subject_id NOT IN(favorite,weak);
  FOR entry IN SELECT d.*,l.lesson_date,l.teacher_id FROM demo2_lessons d JOIN schedule_lesson l ON l.id=d.id LOOP
   IF entry.subject_id=favorite THEN
    marks:=ARRAY[4,4,4, 4,5,4, 4,5,5, 5,5,5];
   ELSIF entry.subject_id=weak THEN
    marks:=ARRAY[3,4,3, 3,3,3, 3,3,3, 2,2,3];
   ELSIF entry.subject_id=middle_subject THEN
    -- Individual number of fours makes every student's total mean distinct.
    marks:=ARRAY[3,3,3, 3,3,3, 3,3,3, 3,3,3];
    FOR h IN 1..pupil.profile LOOP marks[h]:=4; END LOOP;
   ELSE
    marks:=ARRAY[4,4,3, 4,3,4, 4,4,4, 4,3,4];
   END IF;
   value:=marks[(entry.week_no-1)*3+entry.ordinal];
   SELECT q.id INTO STRICT quarter FROM academic_quarter q JOIN academic_year y ON y.id=q.year_id
    WHERE y.school_id=1 AND entry.lesson_date BETWEEN q.starts_on AND q.ends_on;
   SELECT id INTO STRICT actor FROM users WHERE school_id=1 AND teacher_id=entry.teacher_id AND role='TEACHER';
   INSERT INTO demo2_expected VALUES(pupil.id,entry.id,value);
   INSERT INTO journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value,saved_at)
    VALUES(entry.id,pupil.id,actor,quarter,value,entry.lesson_date+time '20:00');
  END LOOP;
 END LOOP;
 INSERT INTO attendance(lesson_id,student_id,status,reason,version)
  SELECT lesson_id,student_id,CASE WHEN seq<=2 THEN 'ABSENT' WHEN seq=3 THEN 'LATE' ELSE 'PRESENT' END,
   'Демонстрационные данные класса 2',0
  FROM(SELECT l.id AS lesson_id,s.id AS student_id,row_number() OVER(PARTITION BY s.id ORDER BY random()) AS seq
   FROM demo2_lessons l CROSS JOIN demo2_students s) randomized;
 -- Rebuild only this script's homework. Other assignments remain unchanged.
 DELETE FROM homework WHERE school_id=1 AND class_id=2 AND request_token LIKE 'demo80-c2-hw-2026-10-%';
 FOR h IN 1..3 LOOP
  SELECT l.*,u.id AS actor INTO STRICT entry FROM demo2_lessons d JOIN schedule_lesson l ON l.id=d.id
   JOIN users u ON u.school_id=1 AND u.teacher_id=l.teacher_id AND u.role='TEACHER'
   WHERE d.week_no=h AND d.ordinal=1 ORDER BY l.subject_id OFFSET h-1 LIMIT 1;
  INSERT INTO homework(school_id,teacher_id,class_id,subject_id,lesson_id,title,body,due_date,mode,published_at,request_token)
   VALUES(1,entry.teacher_id,2,entry.subject_id,entry.id,
    CASE h WHEN 1 THEN 'Практика по теме урока' WHEN 2 THEN 'Закрепление материала' ELSE 'Самостоятельная работа' END,
    'Выполните упражнения по теме урока, объясните решение и проверьте результат.',entry.lesson_date+3,
    'ONLINE',entry.lesson_date+time '14:00','demo80-c2-hw-2026-10-'||h) RETURNING id INTO hw;
  INSERT INTO demo2_homework VALUES(hw);
  INSERT INTO homework_audience SELECT hw,id FROM demo2_students;
  INSERT INTO homework_submission(homework_id,student_id,version,text_answer,status,teacher_comment,submitted_at,checked_at,checked_by)
   SELECT hw,id,1,'Решение упражнений с пояснениями.',CASE WHEN (profile+h)%3=0 THEN 'ACCEPTED' ELSE 'RETURNED' END,
    CASE WHEN (profile+h)%3=0 THEN 'Верно, работа принята.' ELSE 'Исправьте вычисления и дополните пояснения.' END,
    entry.lesson_date+1+time '15:00',entry.lesson_date+2+time '16:00',entry.actor
   FROM demo2_students WHERE (profile+h)%3<>2;
 END LOOP;
 IF (SELECT count(*) FROM demo2_expected)<>336 OR EXISTS(
  SELECT 1 FROM demo2_expected e LEFT JOIN journal_grade g ON g.student_id=e.student_id AND g.lesson_id=e.lesson_id
   WHERE g.id IS NULL OR g.grade_value<>e.grade) THEN RAISE EXCEPTION 'Grade matrix mismatch'; END IF;
 IF (SELECT count(DISTINCT mean) FROM(SELECT avg(grade) AS mean FROM demo2_expected GROUP BY student_id) x)<>7 THEN
  RAISE EXCEPTION 'Student averages must be unique'; END IF;
 IF EXISTS(
  WITH stats AS(SELECT e.student_id,d.subject_id,avg(e.grade) AS mean,
   sum(e.grade) FILTER(WHERE d.week_no=4)-sum(e.grade) FILTER(WHERE d.week_no=3) AS delta
   FROM demo2_expected e JOIN demo2_lessons d ON d.id=e.lesson_id GROUP BY e.student_id,d.subject_id)
  SELECT student_id FROM stats GROUP BY student_id HAVING count(*) FILTER(WHERE mean<3 OR delta<=-1.5)<>1
 ) THEN RAISE EXCEPTION 'Expected exactly one attention subject per student'; END IF;
 IF EXISTS(SELECT 1 FROM demo2_students s WHERE
  (SELECT count(*) FROM attendance a JOIN demo2_lessons l ON l.id=a.lesson_id WHERE a.student_id=s.id AND status='PRESENT')<>45 OR
  (SELECT count(*) FROM attendance a JOIN demo2_lessons l ON l.id=a.lesson_id WHERE a.student_id=s.id AND status='ABSENT')<>2 OR
  (SELECT count(*) FROM attendance a JOIN demo2_lessons l ON l.id=a.lesson_id WHERE a.student_id=s.id AND status='LATE')<>1 OR
  (SELECT count(*) FROM homework_submission hs JOIN demo2_homework h ON h.id=hs.homework_id WHERE hs.student_id=s.id AND status='ACCEPTED')<>1 OR
  (SELECT count(*) FROM homework_submission hs JOIN demo2_homework h ON h.id=hs.homework_id WHERE hs.student_id=s.id AND status='RETURNED')<>1
 ) THEN RAISE EXCEPTION 'Attendance or homework coverage mismatch'; END IF;
END $$;
SELECT s.login,s.full_name,f.name AS favorite,w.name AS attention,round(avg(e.grade),4) AS average,count(*) AS grades
 FROM demo2_students s JOIN demo2_profiles p ON p.student_id=s.id JOIN schedule_subject f ON f.id=p.favorite
 JOIN schedule_subject w ON w.id=p.weak JOIN demo2_expected e ON e.student_id=s.id
 GROUP BY s.profile,s.login,s.full_name,f.name,w.name ORDER BY s.profile;
SELECT s.login,sub.name,d.week_no,array_agg(e.grade ORDER BY d.ordinal) AS marks FROM demo2_expected e
 JOIN demo2_students s ON s.id=e.student_id JOIN demo2_lessons d ON d.id=e.lesson_id JOIN schedule_subject sub ON sub.id=d.subject_id
 GROUP BY s.profile,s.login,sub.name,d.week_no ORDER BY s.profile,sub.name,d.week_no;
COMMIT;
