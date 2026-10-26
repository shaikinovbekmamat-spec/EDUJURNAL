package com.example.edujurnal.service;

import com.example.edujurnal.entity.*;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AcademicService {
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final ScheduleCatalog catalog;
    private final UserService users;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public AcademicService(JdbcTemplate jdbc, ScheduleAccessService access, ScheduleCatalog catalog, UserService users) {
        this.jdbc = jdbc; this.access = access; this.catalog = catalog; this.users = users;
    }

    public long managedSchool(Authentication auth, Long schoolId) {
        User actor = access.currentUser(auth);
        if (actor.getRole() != Role.DIRECTOR && actor.getRole() != Role.OWNER) throw new AccessDeniedException("Управление доступно директору и владельцу.");
        School school = access.school(actor, schoolId);
        if (school == null) throw new ScheduleException("Выберите школу.");
        return school.getId();
    }

    private long directorSchool(Authentication auth) {
        User actor = access.currentUser(auth);
        if (actor.getRole() != Role.DIRECTOR) throw new AccessDeniedException("Только директор управляет учебным процессом.");
        return actor.getSchool().getId();
    }

    public List<Map<String,Object>> years(long school) {
        return jdbc.queryForList("select * from academic_year where school_id=? order by starts_on desc", school);
    }
    public List<Map<String,Object>> quarters(long school) {
        return jdbc.queryForList("select q.*, y.name as year_name from academic_quarter q join academic_year y on y.id=q.year_id where y.school_id=? order by y.starts_on desc,q.number", school);
    }
    public List<Map<String,Object>> accounts(Authentication auth, Long schoolId) {
        long school = managedSchool(auth, schoolId);
        return jdbc.queryForList("select u.id,u.login,u.full_name,u.role,u.student_class_id,u.teacher_id,c.name as class_name from users u left join schedule_class c on c.id=u.student_class_id where u.school_id=? order by u.full_name,u.login", school);
    }
    public List<Map<String,Object>> links(Authentication auth, Long schoolId) {
        long school = managedSchool(auth, schoolId);
        return jdbc.queryForList("select p.parent_id,p.student_id,a.full_name as parent_name,a.login as parent_login,b.full_name as student_name from parent_student p join users a on a.id=p.parent_id join users b on b.id=p.student_id where b.school_id=? order by b.full_name", school);
    }
    public List<Map<String,Object>> assignments(long school) {
        return jdbc.queryForList("select c.name as class_name,s.name as subject_name,t.name as teacher_name from teaching_assignment a join schedule_class c on c.id=a.class_id join schedule_subject s on s.id=a.subject_id join schedule_teacher t on t.id=a.teacher_id where a.school_id=? order by c.name,s.name", school);
    }
    private String name(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length()>max) throw new ScheduleException("Заполните название или ФИО (не более " + max + " символов).");
        return value.trim();
    }
    private long nextCatalogId(String table) {
        long id;
        do { id=jdbc.queryForObject("select nextval('catalog_id_seq')", Long.class); }
        while(jdbc.queryForObject("select count(*) from " + table + " where id=?", Long.class,id)>0);
        return id;
    }
    @Transactional
    public void createCatalog(Authentication auth, String type, String value, int studentCount) {
        long school=directorSchool(auth);
        if (!Set.of("class","subject").contains(type)) throw new ScheduleException("Неизвестный справочник.");
        String title=name(value,100);
        catalog.lockSchool(school);
        if (studentCount<0 || studentCount>100) throw new ScheduleException("Численность класса: от 0 до 100.");
        String table="schedule_"+type;
        if(jdbc.queryForObject("select count(*) from " + table + " where school_id=? and lower(name)=lower(?)",Long.class,school,title)>0) throw new ScheduleException("Такое название уже существует.");
        long id=nextCatalogId(table);
        if(type.equals("class")) jdbc.update("insert into schedule_class(id,school_id,name,student_count) values(?,?,?,?)",id,school,title,studentCount);
        else jdbc.update("insert into schedule_subject(id,school_id,name) values(?,?,?)",id,school,title);
    }
    @Transactional
    public void createYear(Authentication auth, String value, LocalDate start, LocalDate end, List<LocalDate> starts, List<LocalDate> ends) {
        long school=directorSchool(auth);
        String title=name(value,100);
        if(start==null || end==null || !start.isBefore(end) || starts==null || ends==null || starts.size()!=4 || ends.size()!=4) throw new ScheduleException("Укажите учебный год и даты всех четырёх четвертей.");
        LocalDate previous=null;
        for(int i=0;i<4;i++) {
            LocalDate a=starts.get(i), b=ends.get(i);
            if(a==null || b==null || a.isAfter(b) || a.isBefore(start) || b.isAfter(end) || (previous!=null && !a.isAfter(previous))) throw new ScheduleException("Четверти должны идти по порядку, не пересекаться и находиться внутри учебного года.");
            previous=b;
        }
        catalog.lockSchool(school);
        if(jdbc.queryForObject("select count(*) from academic_year where school_id=? and (name=? or (starts_on<=? and ends_on>=?))",Long.class,school,title,end,start)>0) throw new ScheduleException("Учебный год уже существует или пересекается с другим.");
        jdbc.update("insert into academic_year(school_id,name,starts_on,ends_on) values(?,?,?,?)",school,title,start,end);
        long year=jdbc.queryForObject("select id from academic_year where school_id=? and name=?",Long.class,school,title);
        for(int i=0;i<4;i++) jdbc.update("insert into academic_quarter(year_id,number,starts_on,ends_on) values(?,?,?,?)",year,i+1,starts.get(i),ends.get(i));
    }
    @Transactional
    public void assign(Authentication auth,long classId,long subjectId,long teacherId) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_class",classId,school); require("schedule_subject",subjectId,school); require("schedule_teacher",teacherId,school);
        if(jdbc.queryForObject("select count(*) from schedule_teacher_subject where school_id=? and teacher_id=? and subject_id=?",Long.class,school,teacherId,subjectId)==0)
            jdbc.update("insert into schedule_teacher_subject values(?,?,?)",school,teacherId,subjectId);
        if(jdbc.queryForObject("select count(*) from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",Long.class,school,classId,subjectId,teacherId)==0)
            jdbc.update("insert into teaching_assignment values(?,?,?,?)",school,classId,subjectId,teacherId);
    }
    private void require(String table,long id,long school) {
        if(jdbc.queryForObject("select count(*) from "+table+" where id=? and school_id=?",Long.class,id,school)==0) throw new ScheduleException("Выбранная запись не принадлежит вашей школе.");
    }
    @Transactional
    public void configureProfile(Authentication auth,long userId,Long classId,Long teacherId) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("users",userId,school);
        String role=jdbc.queryForObject("select role from users where id=?",String.class,userId);
        if("STUDENT".equals(role)) {
            if(classId==null || teacherId!=null) throw new ScheduleException("Выберите класс ученика.");
            require("schedule_class",classId,school);
            Long previous=jdbc.queryForObject("select student_class_id from users where id=?",Long.class,userId);
            if(!Objects.equals(previous,classId) && (jdbc.queryForObject("select count(*) from journal_grade where student_id=?",Long.class,userId)>0 || jdbc.queryForObject("select count(*) from journal_final_grade where student_id=?",Long.class,userId)>0))
                throw new ScheduleException("У ученика есть сохранённые оценки. Для перевода с сохранением истории требуется отдельная процедура; здесь доступна первичная настройка класса.");
            jdbc.update("update users set student_class_id=? where id=?",classId,userId);
            jdbc.update("update schedule_class set student_count=greatest(student_count,(select count(*) from users where student_class_id=? and role='STUDENT')) where id=?",classId,classId);
        } else if("TEACHER".equals(role)) {
            if(teacherId==null || classId!=null) throw new ScheduleException("Выберите профиль преподавателя.");
            require("schedule_teacher",teacherId,school);
            Long previous=jdbc.queryForObject("select teacher_id from users where id=?",Long.class,userId);
            if(!Objects.equals(previous,teacherId) && (jdbc.queryForObject("select count(*) from journal_grade where teacher_user_id=?",Long.class,userId)>0 || jdbc.queryForObject("select count(*) from journal_final_grade where teacher_user_id=?",Long.class,userId)>0))
                throw new ScheduleException("Профиль учителя с сохранёнными оценками нельзя заменить.");
            if(jdbc.queryForObject("select count(*) from users where teacher_id=? and id<>?",Long.class,teacherId,userId)>0) throw new ScheduleException("Профиль уже связан с другим аккаунтом.");
            jdbc.update("update users set teacher_id=? where id=?",teacherId,userId);
        } else throw new ScheduleException("Учебный профиль настраивается только учителю или ученику.");
    }
    @Transactional
    public void createAccount(Authentication auth,Long schoolId,String login,String password,String fullName,Role role,Long classId,Long teacherId) {
        long school=managedSchool(auth,schoolId);
        User actor=access.currentUser(auth);
        if(role==null || (actor.getRole()==Role.OWNER ? role!=Role.DIRECTOR : !Set.of(Role.TEACHER,Role.STUDENT,Role.PARENT).contains(role))) throw new AccessDeniedException("Вы не можете назначить эту роль.");
        if(login==null || !login.matches("[0-9]{1,50}")) throw new ScheduleException("Логин должен быть уникальным номером: от 1 до 50 цифр.");
        if(password==null || password.length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new ScheduleException("Пароль должен содержать минимум 8 символов и не более 72 байт UTF-8.");
        String full=name(fullName,150);
        catalog.lockSchool(school);
        if(users.findByLogin(login)!=null) throw new ScheduleException("Этот номер-логин уже занят.");
        if(role==Role.STUDENT) {
            if(classId==null) throw new ScheduleException("Выберите класс ученика.");
            require("schedule_class",classId,school);
        } else if(classId!=null) throw new ScheduleException("Класс назначается только ученику.");
        if(role==Role.TEACHER) {
            if(teacherId==null) {
                teacherId=nextCatalogId("schedule_teacher");
                jdbc.update("insert into schedule_teacher(id,school_id,name) values(?,?,?)",teacherId,school,full);
            } else {
                require("schedule_teacher",teacherId,school);
                if(jdbc.queryForObject("select count(*) from users where teacher_id=?",Long.class,teacherId)>0) throw new ScheduleException("У этого преподавателя уже есть аккаунт.");
            }
        } else if(teacherId!=null) throw new ScheduleException("Профиль преподавателя доступен только учителю.");
        User created=users.createUser(login,password,role,full,access.school(actor,school));
        created.setTeacherId(teacherId); created.setStudentClassId(classId);
        if(role==Role.STUDENT) {
            entityManager.flush();
            jdbc.update("update schedule_class set student_count=greatest(student_count,(select count(*) from users where student_class_id=? and role='STUDENT')) where id=? and school_id=?",classId,classId,school);
        }
    }
    @Transactional
    public void linkParent(Authentication auth,Long schoolId,String parentLogin,long studentId) {
        long school=managedSchool(auth,schoolId);
        if(access.currentUser(auth).getRole()!=Role.DIRECTOR) throw new AccessDeniedException("Связь подтверждает директор школы ученика.");
        catalog.lockSchool(school);
        require("users",studentId,school);
        if(!"STUDENT".equals(jdbc.queryForObject("select role from users where id=?",String.class,studentId))) throw new ScheduleException("Выберите ученика.");
        User parent=users.findByLogin(parentLogin);
        if(parent==null || !parent.isEnabled() || !Set.of(Role.PARENT,Role.TEACHER).contains(parent.getRole())) throw new ScheduleException("Активный родитель или учитель с таким логином не найден.");
        if(jdbc.queryForObject("select count(*) from parent_student where parent_id=? and student_id=?",Long.class,parent.getId(),studentId)==0)
            jdbc.update("insert into parent_student(parent_id,student_id,confirmed_by) values(?,?,?)",parent.getId(),studentId,access.currentUser(auth).getId());
    }
    @Transactional
    public void unlinkParent(Authentication auth,long parentId,long studentId) {
        long school=directorSchool(auth);
        require("users",studentId,school);
        jdbc.update("delete from parent_student where parent_id=? and student_id=?",parentId,studentId);
    }
}
