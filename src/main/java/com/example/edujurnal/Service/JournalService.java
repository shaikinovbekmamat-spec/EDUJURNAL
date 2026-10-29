package com.example.edujurnal.service;

import com.example.edujurnal.entity.*;
import com.example.edujurnal.exception.ScheduleException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class JournalService {
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final Clock clock;
    public JournalService(JdbcTemplate jdbc,ScheduleAccessService access,Clock clock) {
        this.jdbc=jdbc; this.access=access; this.clock=clock;
    }
    public record Child(long id,String name,String school) {}
    public record Entry(long lessonId,long studentId,String studentName,String className,String subject,
                        LocalDate date,String time,Integer grade,boolean editable) {}
    public record Period(long id,String name,LocalDate start,LocalDate end) {}
    public record View(List<Child> children,Long childId,List<Period> years,Long yearId,
                       List<Period> quarters,Long quarterId,List<Entry> entries,boolean family,boolean teacher) {}

    public List<Child> children(User user) {
        return jdbc.query("select u.id,u.full_name,s.name from parent_student p join users u on u.id=p.student_id join schedule_school s on s.id=u.school_id where p.parent_id=? and u.role='STUDENT' order by u.full_name",
                (r,n)->new Child(r.getLong(1),r.getString(2),r.getString(3)),user.getId());
    }
    public View view(Authentication auth,boolean family,Long childId,Long yearId,Long quarterId) {
        User user=access.currentUser(auth);
        if(user.getRole()==Role.OWNER) throw new AccessDeniedException("Владельцу доступны аккаунты школ, но не личные оценки учеников.");
        family=user.getRole()==Role.PARENT || (user.getRole()==Role.TEACHER && family);
        List<Child> children=family?children(user):List.of();
        Long student=null;
        long school=user.getSchool().getId();
        if(family) {
            if(childId==null && !children.isEmpty()) childId=children.getFirst().id();
            final Long selected=childId;
            if(selected!=null && children.stream().noneMatch(c->Long.valueOf(c.id()).equals(selected))) throw new AccessDeniedException("Нет подтверждённой связи с этим ребёнком.");
            if(childId==null) return new View(children,null,List.of(),null,List.of(),null,List.of(),true,false);
            student=childId;
            school=jdbc.queryForObject("select school_id from users where id=?",Long.class,childId);
        } else if(user.getRole()==Role.STUDENT) {
            if(childId!=null && !childId.equals(user.getId())) throw new AccessDeniedException("Ученику доступны только собственные оценки.");
            student=user.getId();
        } else if(childId!=null) throw new AccessDeniedException("Выбор ребёнка доступен в разделе семьи.");
        List<Period> years=jdbc.query("select id,name,starts_on,ends_on from academic_year where school_id=? order by starts_on desc",
                (r,n)->new Period(r.getLong(1),r.getString(2),r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class)),school);
        if(yearId==null && !years.isEmpty()) yearId=years.stream().filter(p->!today().isBefore(p.start())&&!today().isAfter(p.end())).findFirst().orElse(years.getFirst()).id();
        final Long selectedYear=yearId;
        Period year=years.stream().filter(p->Long.valueOf(p.id()).equals(selectedYear)).findFirst().orElse(null);
        if(yearId!=null && year==null) throw new AccessDeniedException("Нет доступа к этому учебному году.");
        if(year==null) return new View(children,childId,years,null,List.of(),null,List.of(),family,user.getRole()==Role.TEACHER&&!family);
        List<Period> quarters=jdbc.query("select id,number,starts_on,ends_on from academic_quarter where year_id=? order by number",
                (r,n)->new Period(r.getLong(1),r.getInt(2)+" четверть",r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class)),year.id());
        LocalDate from=year.start(),to=year.end();
        if(quarterId!=null) {
            final Long selectedQuarter=quarterId;
            Period q=quarters.stream().filter(p->Long.valueOf(p.id()).equals(selectedQuarter)).findFirst().orElseThrow(()->new ScheduleException("Четверть не относится к выбранному году."));
            from=q.start(); to=q.end();
        }
        boolean teacher=user.getRole()==Role.TEACHER&&!family;
        String sql="""
            select l.id as lesson_id,u.id as student_id,u.full_name,c.name as class_name,s.name as subject,
                   l.lesson_date,l.start_time,g.grade_value
            from schedule_lesson l
            join schedule_class c on c.id=l.class_id
            join schedule_subject s on s.id=l.subject_id
            join users u on u.school_id=l.school_id and u.role='STUDENT'
            left join journal_grade g on g.lesson_id=l.id and g.student_id=u.id
            where l.school_id=? and l.lesson_date between ? and ?
              and (g.id is not null or (u.student_class_id=l.class_id and
                (l.subgroup_id is null or exists(select 1 from schedule_membership m where m.subgroup_id=l.subgroup_id
                 and m.student_id=u.id and m.active_from<=l.lesson_date and (m.active_to is null or m.active_to>=l.lesson_date)))))
            """;
        List<Object> args=new ArrayList<>(List.of(school,from,to));
        if(student!=null) { sql+=" and u.id=?";args.add(student); }
        if(teacher) {
            sql+=" and l.teacher_id=? and exists(select 1 from teaching_assignment a where a.school_id=l.school_id and a.class_id=l.class_id and a.subject_id=l.subject_id and a.teacher_id=l.teacher_id)";
            args.add(user.getTeacherId());
        }
        sql+=" order by l.lesson_date desc,l.start_time,c.name,s.name,u.full_name,u.id";
        List<Entry> entries=jdbc.query(sql,(r,n)-> {
            LocalDate day=r.getObject("lesson_date",LocalDate.class);
            Integer grade=r.getObject("grade_value",Integer.class);
            return new Entry(r.getLong("lesson_id"),r.getLong("student_id"),r.getString("full_name"),r.getString("class_name"),r.getString("subject"),day,r.getString("start_time"),grade,teacher && grade==null && day.equals(today()) && quarters.stream().anyMatch(q->!day.isBefore(q.start())&&!day.isAfter(q.end())));
        },args.toArray());
        return new View(children,childId,years,yearId,quarters,quarterId,entries,family,teacher);
    }
    public LocalDate today() { return LocalDate.now(clock); }

    @Transactional
    public void saveGrade(Authentication auth,long lessonId,long studentId,int value) {
        User user=access.currentUser(auth);
        if(user.getRole()!=Role.TEACHER || user.getTeacherId()==null) throw new AccessDeniedException("Оценки выставляет назначенный учитель.");
        if(value<1 || value>5) throw new ScheduleException("Оценка должна быть целым числом от 1 до 5.");
        // Use the same school lock as schedule edits so a graded lesson cannot be moved concurrently.
        jdbc.queryForList("select id from schedule_school where id=? for update",user.getSchool().getId());
        // A row lock serializes double submissions and competing writes for this lesson.
        List<Map<String,Object>> lessons=jdbc.queryForList("select * from schedule_lesson where id=? and school_id=? and teacher_id=? for update",lessonId,user.getSchool().getId(),user.getTeacherId());
        if(lessons.isEmpty()) throw new AccessDeniedException("Нет доступа к этому уроку.");
        Map<String,Object> l=lessons.getFirst();
        LocalDate date=((java.sql.Date)l.get("lesson_date")).toLocalDate();
        long classId=((Number)l.get("class_id")).longValue();
        long subjectId=((Number)l.get("subject_id")).longValue();
        if(!date.equals(today())) throw new ScheduleException("Оценку можно выставить только за урок текущего дня по времени Кыргызстана.");
        if(jdbc.queryForObject("select count(*) from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",Long.class,user.getSchool().getId(),classId,subjectId,user.getTeacherId())==0) throw new AccessDeniedException("Учитель не назначен на этот класс и предмет.");
        if(jdbc.queryForObject("select count(*) from users where id=? and school_id=? and student_class_id=? and role='STUDENT' and enabled=true",Long.class,studentId,user.getSchool().getId(),classId)==0) throw new AccessDeniedException("Ученик не относится к этому классу.");
        if(l.get("subgroup_id")!=null && jdbc.queryForObject("select count(*) from schedule_membership where subgroup_id=? and student_id=? and active_from<=? and (active_to is null or active_to>=?)",Long.class,l.get("subgroup_id"),studentId,date,date)==0) throw new AccessDeniedException("Ученик не входит в подгруппу урока.");
        List<Long> quarters=jdbc.queryForList("select q.id from academic_quarter q join academic_year y on y.id=q.year_id where y.school_id=? and ? between q.starts_on and q.ends_on",Long.class,user.getSchool().getId(),date);
        if(quarters.size()!=1) throw new ScheduleException("Для даты урока не настроена учебная четверть. Обратитесь к директору.");
        List<Integer> existing=jdbc.queryForList("select grade_value from journal_grade where lesson_id=? and student_id=?",Integer.class,lessonId,studentId);
        if(!existing.isEmpty()) {
            if(existing.getFirst()==value) return;
            throw new ScheduleException("Оценка уже сохранена. Изменение и отмена запрещены.");
        }
        jdbc.update("insert into journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value) values(?,?,?,?,?)",lessonId,studentId,user.getId(),quarters.getFirst(),value);
    }

    // Методы для классного руководителя
    public Double getClassAverageGrade(Object classIdObj) {
        long classId = ((Number) classIdObj).longValue();
        return jdbc.queryForObject(
            "select avg(grade_value::numeric) from journal_grade g " +
            "join users u on u.id = g.student_id " +
            "where u.student_class_id = ?",
            Double.class, classId
        );
    }

    public Map<String, Object> getClassJournal(long classId, Long quarterId) {
        Map<String, Object> result = new HashMap<>();

        // Получить всех учеников класса
        List<Map<String, Object>> students = jdbc.queryForList(
            "select id, full_name, login from users " +
            "where student_class_id = ? and role = 'STUDENT' " +
            "order by full_name",
            classId
        );

        // Получить все предметы, по которым есть уроки для этого класса
        String subjectSql = "select distinct s.id, s.name from schedule_subject s " +
            "join schedule_lesson l on l.subject_id = s.id " +
            "where l.class_id = ? order by s.name";

        List<Map<String, Object>> subjects = jdbc.queryForList(subjectSql, classId);

        // Получить оценки
        String gradeSql = "select g.student_id, g.grade_value, s.id as subject_id, s.name as subject_name, " +
            "l.lesson_date from journal_grade g " +
            "join schedule_lesson l on l.id = g.lesson_id " +
            "join schedule_subject s on s.id = l.subject_id " +
            "join users u on u.id = g.student_id " +
            "where u.student_class_id = ?";

        List<Object> args = new ArrayList<>();
        args.add(classId);

        if (quarterId != null) {
            gradeSql += " and g.quarter_id = ?";
            args.add(quarterId);
        }

        gradeSql += " order by g.student_id, s.name";

        List<Map<String, Object>> grades = jdbc.query(gradeSql, args.toArray(),
            (rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("student_id", rs.getLong("student_id"));
                row.put("grade_value", rs.getInt("grade_value"));
                row.put("subject_id", rs.getLong("subject_id"));
                row.put("subject_name", rs.getString("subject_name"));
                row.put("lesson_date", rs.getDate("lesson_date"));
                return row;
            }
        );

        // Рассчитать средние баллы по предметам для каждого ученика
        Map<Long, Map<Long, List<Integer>>> studentSubjectGrades = new HashMap<>();
        for (Map<String, Object> grade : grades) {
            long studentId = (Long) grade.get("student_id");
            long subjectId = (Long) grade.get("subject_id");
            int gradeValue = (Integer) grade.get("grade_value");

            studentSubjectGrades
                .computeIfAbsent(studentId, k -> new HashMap<>())
                .computeIfAbsent(subjectId, k -> new ArrayList<>())
                .add(gradeValue);
        }

        // Рассчитать средние баллы
        Map<String, Double> averages = new HashMap<>();
        for (Map.Entry<Long, Map<Long, List<Integer>>> studentEntry : studentSubjectGrades.entrySet()) {
            long studentId = studentEntry.getKey();
            for (Map.Entry<Long, List<Integer>> subjectEntry : studentEntry.getValue().entrySet()) {
                long subjectId = subjectEntry.getKey();
                List<Integer> gradesList = subjectEntry.getValue();
                double avg = gradesList.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                averages.put(studentId + "_" + subjectId, avg);
            }
        }

        result.put("students", students);
        result.put("subjects", subjects);
        result.put("grades", grades);
        result.put("averages", averages);

        return result;
    }
}
