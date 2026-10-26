package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The lesson chooser remains available even before academic periods or rosters are configured. */
@Service
@Transactional(readOnly=true)
public class LessonJournalService {
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final JournalService journal;
    public LessonJournalService(JdbcTemplate jdbc,ScheduleAccessService access,JournalService journal) {
        this.jdbc=jdbc;this.access=access;this.journal=journal;
    }
    public record Lesson(long id,String className,String subject,String time,LocalDate date,
                         long studentCount,boolean assigned,boolean periodReady) {
        public String status() {
            if(!assigned) return "Директору нужно подтвердить назначение на класс и предмет.";
            if(!periodReady) return "Для даты урока не создана учебная четверть. Обратитесь к директору.";
            if(studentCount==0) return "В классе или подгруппе нет активных аккаунтов учеников. Директор должен зачислить учеников.";
            return "Можно открыть список учеников.";
        }
    }
    public record Student(long id,String name,Integer grade,boolean editable) {}
    public record Page(Lesson lesson,List<Student> students,boolean today) {}
    private User teacher(Authentication auth) {
        User user=access.currentUser(auth);
        if(user.getRole()!=Role.TEACHER) throw new AccessDeniedException("Выставление оценок доступно учителю.");
        return user;
    }
    private static final String LESSONS="""
        select l.id,c.name as class_name,s.name as subject,l.start_time,l.lesson_date,
        (select count(*) from users u where u.school_id=l.school_id and u.student_class_id=l.class_id
          and u.role='STUDENT' and u.enabled=true and (l.subgroup_id is null or exists(
          select 1 from schedule_membership m where m.student_id=u.id and m.subgroup_id=l.subgroup_id
          and m.active_from<=l.lesson_date and (m.active_to is null or m.active_to>=l.lesson_date)))) as student_count,
        exists(select 1 from teaching_assignment a where a.school_id=l.school_id and a.class_id=l.class_id
          and a.subject_id=l.subject_id and a.teacher_id=l.teacher_id) as assigned,
        exists(select 1 from academic_quarter q join academic_year y on y.id=q.year_id
          where y.school_id=l.school_id and l.lesson_date between q.starts_on and q.ends_on) as period_ready
        from schedule_lesson l join schedule_class c on c.id=l.class_id join schedule_subject s on s.id=l.subject_id
        where l.school_id=? and l.teacher_id=?
        """;
    private final org.springframework.jdbc.core.RowMapper<Lesson> mapper=(r,n)->new Lesson(
            r.getLong("id"),r.getString("class_name"),r.getString("subject"),r.getString("start_time"),
            r.getObject("lesson_date",LocalDate.class),r.getLong("student_count"),r.getBoolean("assigned"),r.getBoolean("period_ready"));
    public List<Lesson> lessons(Authentication auth,LocalDate date) {
        User user=teacher(auth);
        if(user.getTeacherId()==null) return List.of();
        return jdbc.query(LESSONS+" and l.lesson_date=? order by l.start_time,c.name",mapper,user.getSchool().getId(),user.getTeacherId(),date);
    }
    public Page page(Authentication auth,long lessonId) {
        User user=teacher(auth);
        List<Lesson> available=jdbc.query(LESSONS+" and l.id=?",mapper,user.getSchool().getId(),user.getTeacherId(),lessonId);
        if(available.isEmpty()) throw new AccessDeniedException("Этот урок не назначен вам.");
        Lesson lesson=available.getFirst();
        if(!lesson.assigned()) throw new AccessDeniedException(lesson.status());
        boolean today=lesson.date().equals(journal.today());
        List<Student> students=jdbc.query("""
            select u.id,u.full_name,g.grade_value from schedule_lesson l
            join users u on u.school_id=l.school_id and u.role='STUDENT'
            left join journal_grade g on g.lesson_id=l.id and g.student_id=u.id
            where l.id=? and (g.id is not null or (u.enabled=true and u.student_class_id=l.class_id
            and (l.subgroup_id is null or exists(select 1 from schedule_membership m
            where m.student_id=u.id and m.subgroup_id=l.subgroup_id and m.active_from<=l.lesson_date
            and (m.active_to is null or m.active_to>=l.lesson_date))))) order by u.full_name,u.id
            """,(r,n)->new Student(r.getLong("id"),r.getString("full_name"),r.getObject("grade_value",Integer.class),
                today && lesson.periodReady() && r.getObject("grade_value")==null),lessonId);
        return new Page(lesson,students,today);
    }
}
