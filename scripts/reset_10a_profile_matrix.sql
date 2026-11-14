-- Mixed subject profiles for the same demonstration period, 12 October - 8 November 2026.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT pg_advisory_xact_lock(801001);
CREATE TEMP TABLE matrix_students ON COMMIT DROP AS
 SELECT id,login,row_number() OVER(ORDER BY substring(login FROM '[0-9]+$')::int,id)::int AS profile
 FROM users WHERE school_id=1 AND student_class_id=1 AND role='STUDENT';
CREATE TEMP TABLE matrix_lessons ON COMMIT DROP AS
 WITH candidates AS (
  SELECT l.*,s.name AS subject_name,
   ((l.lesson_date-DATE '2026-10-12')/7+1)::int AS week_no,
   (extract(isodow FROM l.lesson_date)::int+1)/2 AS ordinal,
   row_number() OVER(PARTITION BY l.subject_id,l.lesson_date ORDER BY l.start_time,l.id) AS pick
  FROM schedule_lesson l JOIN schedule_subject s ON s.id=l.subject_id
  WHERE l.school_id=1 AND l.class_id=1 AND l.subgroup_id IS NULL
   AND l.lesson_date>=DATE '2026-10-12'
   AND l.lesson_date<DATE '2026-11-09'
   AND extract(isodow FROM l.lesson_date) IN(1,3,5)
   AND s.name IN('Английский язык','Физика','Математика','Русский язык')
 ) SELECT * FROM candidates WHERE pick=1;
CREATE TEMP TABLE expected_matrix(student_id bigint,lesson_id bigint,grade int,PRIMARY KEY(student_id,lesson_id)) ON COMMIT DROP;
CREATE TEMP TABLE matrix_homework(id bigint) ON COMMIT DROP;
DO $$
DECLARE
 pupil record; entry record; marks int[]; value int; quarter bigint; actor bigint;
 h int; hw bigint; favorite text; weak text; middle_subject text;
BEGIN
 IF (SELECT count(*) FROM matrix_students)<>8 THEN RAISE EXCEPTION 'Expected exactly eight students'; END IF;
 IF (SELECT count(*) FROM matrix_lessons)<>48 OR
 EXISTS(SELECT 1 FROM matrix_lessons GROUP BY subject_id,week_no HAVING count(*)<>3)
 THEN RAISE EXCEPTION 'Expected three lessons per subject in each of four weeks'; END IF;
 ALTER TABLE journal_grade DISABLE TRIGGER journal_grade_immutable;
 DELETE FROM journal_grade WHERE student_id IN(SELECT id FROM matrix_students);
 ALTER TABLE journal_grade ENABLE TRIGGER journal_grade_immutable;
 DELETE FROM attendance_notification WHERE student_id IN(SELECT id FROM matrix_students);
 DELETE FROM attendance_history WHERE student_id IN(SELECT id FROM matrix_students);
 DELETE FROM attendance WHERE student_id IN(SELECT id FROM matrix_students);
 DELETE FROM homework_submission WHERE student_id IN(SELECT id FROM matrix_students);
 DELETE FROM homework_paper_mark WHERE student_id IN(SELECT id FROM matrix_students);
 DELETE FROM homework_audience WHERE student_id IN(SELECT id FROM matrix_students);
 -- Preserve assignments with recipients outside this class.
 DELETE FROM homework h WHERE h.school_id=1 AND h.class_id=1
  AND NOT EXISTS(SELECT 1 FROM homework_audience a WHERE a.homework_id=h.id);
 FOR pupil IN SELECT * FROM matrix_students ORDER BY profile LOOP
  favorite:=(ARRAY['Английский язык','Математика','Русский язык','Физика','Русский язык','Английский язык','Математика','Физика'])[pupil.profile];
  weak:=(ARRAY['Физика','Русский язык','Математика','Английский язык','Физика','Математика','Английский язык','Русский язык'])[pupil.profile];
  SELECT min(subject_name) INTO middle_subject FROM matrix_lessons WHERE subject_name NOT IN(favorite,weak);
  FOR entry IN SELECT * FROM matrix_lessons ORDER BY lesson_date,start_time,id LOOP
   IF entry.subject_name=favorite THEN
    marks:=CASE pupil.profile%4
     WHEN 1 THEN ARRAY[4,4,4, 4,5,4, 5,5,4, 5,5,5]
     WHEN 2 THEN ARRAY[4,5,4, 4,5,5, 5,5,5, 5,5,5]
     WHEN 3 THEN ARRAY[5,4,5, 5,5,5, 5,4,5, 5,5,5]
     ELSE ARRAY[4,4,5, 4,5,5, 5,5,5, 5,5,5] END;
   ELSIF entry.subject_name=weak THEN
    marks:=CASE pupil.profile%4
     WHEN 1 THEN ARRAY[4,4,3, 3,3,4, 3,3,3, 2,3,2]
     WHEN 2 THEN ARRAY[3,2,3, 3,3,2, 2,3,2, 2,2,3]
     WHEN 3 THEN ARRAY[4,3,4, 4,3,3, 3,3,3, 2,2,3]
     ELSE ARRAY[3,3,2, 3,2,3, 2,2,3, 2,3,2] END;
   ELSIF entry.subject_name=middle_subject THEN
    marks:=CASE WHEN pupil.profile%2=1
     THEN ARRAY[4,3,4, 4,4,4, 3,4,4, 4,4,4]
     ELSE ARRAY[3,4,3, 4,3,4, 4,4,3, 4,3,4] END;
   ELSE
    marks:=CASE WHEN pupil.profile%2=1
     THEN ARRAY[3,3,4, 3,4,3, 4,3,4, 3,4,4]
     ELSE ARRAY[4,4,3, 4,4,4, 4,3,4, 4,4,4] END;
   END IF;
   value:=marks[(entry.week_no-1)*3+entry.ordinal];
   SELECT q.id INTO STRICT quarter FROM academic_quarter q JOIN academic_year y ON y.id=q.year_id
    WHERE y.school_id=1 AND entry.lesson_date BETWEEN q.starts_on AND q.ends_on;
   SELECT id INTO STRICT actor FROM users WHERE school_id=1 AND teacher_id=entry.teacher_id AND role='TEACHER';
   INSERT INTO expected_matrix VALUES(pupil.id,entry.id,value);
   INSERT INTO journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value,saved_at)
    VALUES(entry.id,pupil.id,actor,quarter,value,entry.lesson_date+time '20:00');
  END LOOP;
 END LOOP;
 INSERT INTO attendance(lesson_id,student_id,status,reason,version)
  SELECT lesson_id,student_id,CASE WHEN seq<=2 THEN 'ABSENT' WHEN seq=3 THEN 'LATE' ELSE 'PRESENT' END,
   'Демонстрационные данные по индивидуальным профилям',0
  FROM(SELECT l.id AS lesson_id,s.id AS student_id,row_number() OVER(PARTITION BY s.id ORDER BY random()) AS seq
   FROM matrix_students s CROSS JOIN matrix_lessons l) randomized;
 FOR h IN 1..3 LOOP
  SELECT l.*,u.id AS actor INTO STRICT entry FROM matrix_lessons l
   JOIN users u ON u.school_id=1 AND u.teacher_id=l.teacher_id AND u.role='TEACHER'
   WHERE l.week_no=h AND l.ordinal=1 ORDER BY l.subject_id LIMIT 1;
  INSERT INTO homework(school_id,teacher_id,class_id,subject_id,lesson_id,title,body,due_date,mode,published_at,request_token)
   VALUES(1,entry.teacher_id,1,entry.subject_id,entry.id,
    CASE h WHEN 1 THEN 'Практическая работа по теме' WHEN 2 THEN 'Закрепление материала' ELSE 'Самостоятельная работа' END,
    'Решите упражнения по теме урока и объясните ход решения.',entry.lesson_date+3,'ONLINE',entry.lesson_date+time '14:00',
    'matrix80-10a-2026-11-09-'||h) RETURNING id INTO hw;
  INSERT INTO matrix_homework VALUES(hw);
  INSERT INTO homework_audience SELECT hw,id FROM matrix_students;
  INSERT INTO homework_submission(homework_id,student_id,version,text_answer,status,teacher_comment,submitted_at,checked_at,checked_by)
   SELECT hw,id,1,'Решение с пояснениями.',
    CASE (profile+h)%3 WHEN 0 THEN 'ACCEPTED' ELSE 'RETURNED' END,
    CASE (profile+h)%3 WHEN 0 THEN 'Все верно, работа принята.' ELSE 'Исправьте вычисления и поясните ответ.' END,
    entry.lesson_date+1+time '15:00',entry.lesson_date+2+time '16:00',entry.actor
   FROM matrix_students WHERE (profile+h)%3<>2;
 END LOOP;
 IF (SELECT count(*) FROM journal_grade g JOIN matrix_students s ON s.id=g.student_id)<>384 OR
 EXISTS(SELECT 1 FROM expected_matrix e LEFT JOIN journal_grade g ON g.lesson_id=e.lesson_id AND g.student_id=e.student_id
  WHERE g.id IS NULL OR g.grade_value<>e.grade)
 THEN RAISE EXCEPTION 'Grade matrix verification failed'; END IF;
 IF EXISTS(SELECT 1 FROM matrix_students s WHERE
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='ABSENT')<>2 OR
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='LATE')<>1 OR
  (SELECT count(*) FROM attendance WHERE student_id=s.id AND status='PRESENT')<>45 OR
  (SELECT count(*) FROM homework_submission WHERE student_id=s.id AND status='ACCEPTED')<>1 OR
  (SELECT count(*) FROM homework_submission WHERE student_id=s.id AND status='RETURNED')<>1 OR
  (SELECT count(*) FROM homework_submission WHERE student_id=s.id)<>2)
 THEN RAISE EXCEPTION 'Attendance or homework verification failed'; END IF;
 -- With three grades in every completed week, the application's final comparison is N4 vs N3.
 IF EXISTS(
  WITH subject_stats AS(
   SELECT e.student_id,l.subject_id,avg(e.grade) AS average,
    sum(e.grade) FILTER(WHERE l.week_no=4)-sum(e.grade) FILTER(WHERE l.week_no=3) AS last_sum_change
   FROM expected_matrix e JOIN matrix_lessons l ON l.id=e.lesson_id GROUP BY e.student_id,l.subject_id
  ) SELECT student_id FROM subject_stats GROUP BY student_id
   HAVING count(*) FILTER(WHERE average<3 OR last_sum_change<=-1.5) NOT BETWEEN 1 AND 2
 ) THEN RAISE EXCEPTION 'Expected one or two attention subjects per student'; END IF;
 IF EXISTS(
  WITH lines AS(
   SELECT e.student_id,l.subject_id,array_agg(e.grade ORDER BY l.week_no,l.ordinal) AS marks
   FROM expected_matrix e JOIN matrix_lessons l ON l.id=e.lesson_id GROUP BY e.student_id,l.subject_id
  ) SELECT student_id FROM lines GROUP BY student_id HAVING count(DISTINCT lines.marks)<>4
 ) THEN RAISE EXCEPTION 'Each subject must have a distinct trajectory'; END IF;
END $$;
SELECT s.login,s.profile,count(*) AS grades,round(avg(g.grade_value),4) AS average
 FROM matrix_students s JOIN journal_grade g ON g.student_id=s.id GROUP BY s.login,s.profile ORDER BY s.profile;
SELECT s.login,l.subject_name,l.week_no,array_agg(g.grade_value ORDER BY l.ordinal) AS grades
 FROM matrix_students s JOIN journal_grade g ON g.student_id=s.id JOIN matrix_lessons l ON l.id=g.lesson_id
 GROUP BY s.login,s.profile,l.subject_name,l.week_no ORDER BY s.profile,l.subject_name,l.week_no;
COMMIT;
