package com.example.edujurnal.service;

import com.example.edujurnal.entity.*;
import com.example.edujurnal.exception.ScheduleException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class FinalGradeService {
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final JournalService journal;
    public FinalGradeService(JdbcTemplate jdbc,ScheduleAccessService access,JournalService journal) {
        this.jdbc=jdbc;this.access=access;this.journal=journal;
    }
    public record FinalMark(long studentId,String studentName,long subjectId,String subject,String className,Integer value,boolean editable) {}
    public List<FinalMark> list(Authentication auth,boolean family,Long childId,Long yearId,Long quarterId) {
        var view=journal.view(auth,family,childId,yearId,quarterId);
        if(view.yearId()==null) return List.of();
        User user=access.currentUser(auth);
        long school=jdbc.queryForObject("select school_id from academic_year where id=?",Long.class,view.yearId());
        int number=period(view.yearId(),quarterId);
        Long student=view.family()?view.childId():(user.getRole()==Role.STUDENT?user.getId():null);
        String sql="""
            select distinct u.id,u.full_name,s.id as subject_id,s.name as subject,c.name as class_name,f.grade_value
            from users u
            join schedule_subject s on s.school_id=u.school_id
            left join journal_final_grade f on f.student_id=u.id and f.subject_id=s.id and f.year_id=? and f.period_number=?
            join schedule_class c on c.id=coalesce(f.class_id,u.student_class_id)
            where u.school_id=? and u.role='STUDENT'
            and (f.id is not null or exists(select 1 from teaching_assignment a where a.school_id=u.school_id and a.class_id=u.student_class_id and a.subject_id=s.id))
            """;
        List<Object> args=new ArrayList<>(List.of(view.yearId(),number,school));
        if(student!=null) {sql+=" and u.id=?";args.add(student);}
        if(view.teacher()) {
            sql+=" and exists(select 1 from teaching_assignment a where a.school_id=u.school_id and a.class_id=c.id and a.subject_id=s.id and a.teacher_id=?)";
            args.add(user.getTeacherId());
        }
        sql+=" order by c.name,s.name,u.full_name,u.id";
        return jdbc.query(sql,(r,n)->new FinalMark(r.getLong("id"),r.getString("full_name"),r.getLong("subject_id"),r.getString("subject"),r.getString("class_name"),r.getObject("grade_value",Integer.class),view.teacher() && r.getObject("grade_value")==null),args.toArray());
    }
    private int period(long year,Long quarter) {
        if(quarter==null) return 0;
        List<Integer> numbers=jdbc.queryForList("select number from academic_quarter where id=? and year_id=?",Integer.class,quarter,year);
        if(numbers.isEmpty()) throw new ScheduleException("Четверть не относится к учебному году.");
        return numbers.getFirst();
    }
    @Transactional
    public void save(Authentication auth,long studentId,long subjectId,long yearId,Long quarterId,int value) {
        User user=access.currentUser(auth);
        if(user.getRole()!=Role.TEACHER || user.getTeacherId()==null) throw new AccessDeniedException("Итог подтверждает назначенный учитель.");
        if(value<1 || value>5) throw new ScheduleException("Итоговая оценка должна быть от 1 до 5.");
        long school=user.getSchool().getId();
        jdbc.queryForList("select id from schedule_school where id=? for update",school);
        if(jdbc.queryForObject("select count(*) from academic_year where id=? and school_id=?",Long.class,yearId,school)==0) throw new AccessDeniedException("Нет доступа к этому учебному году.");
        int number=period(yearId,quarterId);
        List<Long> classes=jdbc.queryForList("""
            select u.student_class_id from users u join teaching_assignment a on a.class_id=u.student_class_id and a.school_id=u.school_id
            where u.id=? and u.role='STUDENT' and u.enabled=true and u.school_id=? and a.subject_id=? and a.teacher_id=?
            """,Long.class,studentId,school,subjectId,user.getTeacherId());
        if(classes.isEmpty()) throw new AccessDeniedException("Нет назначения на класс и предмет этого ученика.");
        List<Integer> existing=jdbc.queryForList("select grade_value from journal_final_grade where student_id=? and subject_id=? and year_id=? and period_number=?",Integer.class,studentId,subjectId,yearId,number);
        if(!existing.isEmpty()) {
            if(existing.getFirst()==value) return;
            throw new ScheduleException("Итог уже подтверждён. Изменение и отмена запрещены.");
        }
        jdbc.update("insert into journal_final_grade(year_id,period_number,school_id,class_id,subject_id,student_id,teacher_user_id,grade_value) values(?,?,?,?,?,?,?,?)",yearId,number,school,classes.getFirst(),subjectId,studentId,user.getId(),value);
    }
}
