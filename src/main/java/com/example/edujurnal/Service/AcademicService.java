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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
        return jdbc.queryForList("select a.class_id,a.subject_id,a.teacher_id,c.name as class_name,s.name as subject_name,t.name as teacher_name from teaching_assignment a join schedule_class c on c.id=a.class_id join schedule_subject s on s.id=a.subject_id join schedule_teacher t on t.id=a.teacher_id where a.school_id=? order by c.name,s.name,t.name", school);
    }
    public List<Map<String,Object>> classTeachers(Authentication auth, Long schoolId) {
        long school=managedSchool(auth,schoolId);
        return jdbc.queryForList("""
                select c.id as class_id,c.name as class_name,a.teacher_user_id,
                       u.full_name as teacher_name,u.login as teacher_login,u.enabled as teacher_enabled
                from schedule_class c left join class_teacher a on a.class_id=c.id and a.school_id=c.school_id
                left join users u on u.id=a.teacher_user_id and u.school_id=c.school_id
                where c.school_id=? order by c.name,c.id
                """,school);
    }
    public List<Map<String,Object>> eligibleClassTeachers(Authentication auth, Long schoolId) {
        long school=managedSchool(auth,schoolId);
        return jdbc.queryForList("select id,full_name,login from users where school_id=? and role='TEACHER' and enabled=true order by full_name,login",school);
    }
    @Transactional
    public void assignClassTeacher(Authentication auth,long classId,Long teacherUserId) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_class",classId,school);
        if(teacherUserId!=null && jdbc.queryForObject("select count(*) from users where id=? and school_id=? and role='TEACHER' and enabled=true",Long.class,teacherUserId,school)==0)
            throw new ScheduleException("Выберите активного учителя вашей школы.");
        if(teacherUserId==null) {
            jdbc.update("delete from class_teacher where school_id=? and class_id=?",school,classId);
        } else if(jdbc.update("update class_teacher set teacher_user_id=? where school_id=? and class_id=?",teacherUserId,school,classId)==0) {
            jdbc.update("insert into class_teacher(school_id,class_id,teacher_user_id) values(?,?,?)",school,classId,teacherUserId);
        }
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
    public void updateClass(Authentication auth, long id, String value, int studentCount) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_class",id,school);
        String title=name(value,100);
        if(studentCount<0 || studentCount>100) throw new ScheduleException("Численность класса: от 0 до 100.");
        if(jdbc.queryForObject("select count(*) from schedule_class where school_id=? and id<>? and lower(name)=lower(?)",Long.class,school,id,title)>0)
            throw new ScheduleException("Класс с таким названием уже существует.");
        if(jdbc.queryForObject("select count(*) from users where student_class_id=? and role='STUDENT'",Long.class,id)>studentCount)
            throw new ScheduleException("Численность не может быть меньше количества зачисленных учеников.");
        if(jdbc.queryForObject("select count(*) from schedule_lesson l join schedule_room r on r.id=l.room_id where l.school_id=? and l.class_id=? and l.subgroup_id is null and r.capacity<?",Long.class,school,id,studentCount)>0)
            throw new ScheduleException("Численность превышает вместимость кабинета в расписании класса. Сначала измените кабинет.");
        jdbc.update("update schedule_class set name=?,student_count=? where id=? and school_id=?",title,studentCount,id,school);
    }
    @Transactional
    public void deleteClass(Authentication auth, long id) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_class",id,school);
        if(jdbc.queryForObject("select count(*) from users where student_class_id=?",Long.class,id)>0)
            throw new ScheduleException("В классе есть ученики. Удаление запрещено.");
        for(String table:List.of("schedule_subgroup","schedule_lesson","teaching_assignment","journal_final_grade","class_teacher")) {
            if(jdbc.queryForObject("select count(*) from "+table+" where school_id=? and class_id=?",Long.class,school,id)>0)
                throw new ScheduleException("Класс используется в подгруппах, назначениях, расписании или оценках. Удаление запрещено.");
        }
        jdbc.update("delete from schedule_class where id=? and school_id=?",id,school);
    }
    @Transactional
    public void updateSubject(Authentication auth, long id, String value) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_subject",id,school);
        String title=name(value,100);
        if(jdbc.queryForObject("select count(*) from schedule_subject where school_id=? and id<>? and lower(name)=lower(?)",Long.class,school,id,title)>0)
            throw new ScheduleException("Предмет с таким названием уже существует.");
        jdbc.update("update schedule_subject set name=? where id=? and school_id=?",title,id,school);
    }
    @Transactional
    public void deleteSubject(Authentication auth, long id) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("schedule_subject",id,school);
        for(String table:List.of("schedule_lesson","schedule_teacher_subject","teaching_assignment","journal_final_grade")) {
            if(jdbc.queryForObject("select count(*) from "+table+" where school_id=? and subject_id=?",Long.class,school,id)>0)
                throw new ScheduleException("Предмет используется в назначениях, расписании или итоговых оценках. Удаление запрещено; можно исправить название.");
        }
        jdbc.update("delete from schedule_subject where id=? and school_id=?",id,school);
    }
    private void validateYearDates(LocalDate start, LocalDate end, List<LocalDate> starts, List<LocalDate> ends) {
        if(start==null || end==null || !start.isBefore(end) || starts==null || ends==null || starts.size()!=4 || ends.size()!=4)
            throw new ScheduleException("Укажите учебный год и даты всех четырёх четвертей.");
        LocalDate previous=null;
        for(int i=0;i<4;i++) {
            LocalDate a=starts.get(i), b=ends.get(i);
            if(a==null || b==null || a.isAfter(b) || a.isBefore(start) || b.isAfter(end) || (previous!=null && !a.isAfter(previous)))
                throw new ScheduleException("Четверти должны идти по порядку, не пересекаться и находиться внутри учебного года.");
            previous=b;
        }
    }
    private boolean yearHasGrades(long year) {
        return jdbc.queryForObject("select count(*) from journal_grade g join academic_quarter q on q.id=g.quarter_id where q.year_id=?",Long.class,year)>0
                || jdbc.queryForObject("select count(*) from journal_final_grade where year_id=?",Long.class,year)>0;
    }
    @Transactional
    public void updateYear(Authentication auth,long id,String value,LocalDate start,LocalDate end,List<LocalDate> starts,List<LocalDate> ends) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("academic_year",id,school);
        String title=name(value,100);
        validateYearDates(start,end,starts,ends);
        if(jdbc.queryForObject("select count(*) from academic_year where school_id=? and id<>? and (lower(name)=lower(?) or (starts_on<=? and ends_on>=?))",Long.class,school,id,title,end,start)>0)
            throw new ScheduleException("Учебный год уже существует или пересекается с другим.");
        var old=jdbc.queryForMap("select starts_on,ends_on from academic_year where id=?",id);
        var quarters=jdbc.queryForList("select starts_on,ends_on from academic_quarter where year_id=? order by number",id);
        if(quarters.size()!=4) throw new ScheduleException("У года должны быть четыре четверти. Обратитесь к администратору.");
        boolean changed=!start.equals(((java.sql.Date)old.get("starts_on")).toLocalDate()) || !end.equals(((java.sql.Date)old.get("ends_on")).toLocalDate());
        for(int i=0;i<4;i++) changed |= !starts.get(i).equals(((java.sql.Date)quarters.get(i).get("starts_on")).toLocalDate()) || !ends.get(i).equals(((java.sql.Date)quarters.get(i).get("ends_on")).toLocalDate());
        if(changed && yearHasGrades(id)) throw new ScheduleException("В учебном году есть сохранённые оценки. Даты года и четвертей изменять нельзя; название можно исправить.");
        jdbc.update("update academic_year set name=?,starts_on=?,ends_on=? where id=?",title,start,end,id);
        for(int i=0;i<4;i++) jdbc.update("update academic_quarter set starts_on=?,ends_on=? where year_id=? and number=?",starts.get(i),ends.get(i),id,i+1);
    }
    @Transactional
    public void deleteYear(Authentication auth,long id) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        require("academic_year",id,school);
        if(yearHasGrades(id)) throw new ScheduleException("Учебный год содержит оценки и не может быть удалён.");
        if(jdbc.queryForObject("select count(*) from schedule_lesson l join academic_year y on y.school_id=l.school_id and l.lesson_date between y.starts_on and y.ends_on where y.id=?",Long.class,id)>0)
            throw new ScheduleException("В датах учебного года есть уроки. Удаление запрещено, чтобы сохранить учебный календарь.");
        jdbc.update("delete from academic_quarter where year_id=?",id);
        jdbc.update("delete from academic_year where id=? and school_id=?",id,school);
    }
    @Transactional
    public void createYear(Authentication auth, String value, LocalDate start, LocalDate end, List<LocalDate> starts, List<LocalDate> ends) {
        long school=directorSchool(auth);
        String title=name(value,100);
        validateYearDates(start,end,starts,ends);
        catalog.lockSchool(school);
        if(jdbc.queryForObject("select count(*) from academic_year where school_id=? and (lower(name)=lower(?) or (starts_on<=? and ends_on>=?))",Long.class,school,title,end,start)>0) throw new ScheduleException("Учебный год уже существует или пересекается с другим.");
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
    @Transactional
    public void updateAssignment(Authentication auth,long oldClassId,long oldSubjectId,long oldTeacherId,
                                 long classId,long subjectId,long teacherId) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        requireAssignment(school,oldClassId,oldSubjectId,oldTeacherId);
        if(oldClassId==classId && oldSubjectId==subjectId && oldTeacherId==teacherId) return;
        if(jdbc.queryForObject("select count(*) from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",Long.class,school,classId,subjectId,teacherId)>0)
            throw new ScheduleException("Такое назначение уже существует.");
        assign(auth,classId,subjectId,teacherId);
        jdbc.update("delete from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",school,oldClassId,oldSubjectId,oldTeacherId);
    }
    @Transactional
    public void deleteAssignment(Authentication auth,long classId,long subjectId,long teacherId) {
        long school=directorSchool(auth);
        catalog.lockSchool(school);
        requireAssignment(school,classId,subjectId,teacherId);
        jdbc.update("delete from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",school,classId,subjectId,teacherId);
    }
    private void requireAssignment(long school,long classId,long subjectId,long teacherId) {
        if(jdbc.queryForObject("select count(*) from teaching_assignment where school_id=? and class_id=? and subject_id=? and teacher_id=?",Long.class,school,classId,subjectId,teacherId)==0)
            throw new ScheduleException("Назначение не найдено в вашей школе. Обновите страницу.");
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
    public void updateAccount(Authentication auth,Long schoolId,long id,String login,String fullName,String password,Long classId) {
        long school=managedSchool(auth,schoolId);
        catalog.lockSchool(school);
        User target=editableAccount(auth,school,id);
        String full=name(fullName,150);
        if(!Objects.equals(login,target.getLogin()) && (login==null || !login.matches("[0-9]{1,50}")))
            throw new ScheduleException("Логин должен быть номером: от 1 до 50 цифр.");
        User duplicate=users.findByLogin(login);
        if(duplicate!=null && !duplicate.getId().equals(id)) throw new ScheduleException("Этот номер-логин уже занят.");
        if(password!=null && !password.isEmpty()) {
            if(password.length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
                throw new ScheduleException("Пароль должен содержать минимум 8 символов и не более 72 байт UTF-8.");
            target.setPassword(passwordEncoder.encode(password));
            target.setFailedAttempts(0);
            target.setLockedUntil(null);
        }
        if(target.getRole()==Role.STUDENT && !Objects.equals(classId,target.getStudentClassId())) {
            configureProfile(auth,id,classId,null);
            target.setStudentClassId(classId);
        } else if(target.getRole()!=Role.STUDENT && classId!=null) throw new ScheduleException("Класс назначается только ученику.");
        target.setLogin(login);
        target.setFullName(full);
        if(target.getTeacherId()!=null)
            jdbc.update("update schedule_teacher set name=? where id=? and school_id=?",full,target.getTeacherId(),school);
        entityManager.flush();
    }
    @Transactional
    public void deleteAccount(Authentication auth,Long schoolId,long id) {
        long school=managedSchool(auth,schoolId);
        catalog.lockSchool(school);
        User target=editableAccount(auth,school,id);
        for(String table:List.of("journal_grade","journal_final_grade")) {
            if(jdbc.queryForObject("select count(*) from "+table+" where student_id=? or teacher_user_id=?",Long.class,id,id)>0)
                throw new ScheduleException("У пользователя есть сохранённые оценки. Удаление запрещено для сохранения истории журнала.");
        }
        if(jdbc.queryForObject("select count(*) from parent_student where confirmed_by=? and parent_id<>? and student_id<>?",Long.class,id,id,id)>0)
            throw new ScheduleException("Пользователь подтверждал связи родителей. Сначала переназначьте эти связи.");
        jdbc.update("delete from parent_student where parent_id=? or student_id=?",id,id);
        jdbc.update("delete from class_teacher where teacher_user_id=?",id);
        jdbc.update("delete from schedule_membership where student_id=?",id);
        entityManager.remove(target);
        entityManager.flush();
    }
    private User editableAccount(Authentication auth,long school,long id) {
        require("users",id,school);
        User actor=access.currentUser(auth);
        User target=entityManager.find(User.class,id);
        if(actor.getId().equals(id) || (actor.getRole()==Role.OWNER ? target.getRole()!=Role.DIRECTOR
                : !Set.of(Role.TEACHER,Role.STUDENT,Role.PARENT).contains(target.getRole())))
            throw new AccessDeniedException("Вы не можете изменять или удалять эту учётную запись.");
        return target;
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

    // Методы для классного руководителя
    public Map<String, Object> getManagedClass(Authentication auth) {
        User user = access.currentUser(auth);
        if (user.getRole() != Role.TEACHER) return null;

        List<Map<String, Object>> result = jdbc.queryForList(
            "select c.id as class_id, c.name as class_name from class_teacher ct " +
            "join schedule_class c on c.id = ct.class_id " +
            "where ct.teacher_user_id = ? and ct.school_id = ?",
            user.getId(), user.getSchool().getId()
        );

        return result.isEmpty() ? null : result.get(0);
    }

    public int getStudentsCount(Object classIdObj) {
        long classId = ((Number) classIdObj).longValue();
        return jdbc.queryForObject(
            "select count(*) from users where student_class_id = ? and role = 'STUDENT'",
            Integer.class, classId
        );
    }

    public List<Map<String, Object>> getQuartersForClass(long classId) {
        return jdbc.queryForList(
            "select q.id, q.number, q.starts_on, q.ends_on, y.name as year_name " +
            "from academic_quarter q " +
            "join academic_year y on y.id = q.year_id " +
            "join schedule_class c on c.school_id = y.school_id " +
            "where c.id = ? order by y.starts_on desc, q.number",
            classId
        );
    }

    public List<Map<String, Object>> getClassStudents(long classId) {
        return jdbc.queryForList(
            "select id, full_name, login from users " +
            "where student_class_id = ? and role = 'STUDENT' " +
            "order by full_name, login",
            classId
        );
    }

    public List<Map<String, Object>> getParentLinks(long classId) {
        return jdbc.queryForList(
            "select p.parent_id, p.student_id, " +
            "pa.full_name as parent_name, pa.login as parent_login, " +
            "s.full_name as student_name " +
            "from parent_student p " +
            "join users s on s.id = p.student_id " +
            "join users pa on pa.id = p.parent_id " +
            "where s.student_class_id = ? and s.role = 'STUDENT' " +
            "order by s.full_name, pa.full_name",
            classId
        );
    }

    @Transactional
    public void linkParentByClassTeacher(Authentication auth, String parentLogin, long studentId) {
        User teacher = access.currentUser(auth);
        if (teacher.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Только классный руководитель может подтверждать связи.");
        }

        // Проверить, что учитель - классный руководитель этого ученика
        Long classId = jdbc.queryForObject(
            "select student_class_id from users where id = ?",
            Long.class, studentId
        );

        if (classId == null) {
            throw new ScheduleException("Ученик не зачислен в класс.");
        }

        Long teacherClassId = jdbc.queryForObject(
            "select class_id from class_teacher where teacher_user_id = ? and school_id = ?",
            Long.class, teacher.getId(), teacher.getSchool().getId()
        );

        if (teacherClassId == null || !teacherClassId.equals(classId)) {
            throw new AccessDeniedException("Вы не являетесь классным руководителем этого ученика.");
        }

        // Проверить существование родителя
        User parent = users.findByLogin(parentLogin);
        if (parent == null || !parent.isEnabled() ||
            !Set.of(Role.PARENT, Role.TEACHER).contains(parent.getRole())) {
            throw new ScheduleException("Активный родитель или учитель с таким логином не найден.");
        }

        // Создать связь
        if (jdbc.queryForObject(
            "select count(*) from parent_student where parent_id = ? and student_id = ?",
            Long.class, parent.getId(), studentId) == 0) {
            jdbc.update(
                "insert into parent_student(parent_id, student_id, confirmed_by) values(?, ?, ?)",
                parent.getId(), studentId, teacher.getId()
            );
        }
    }

    @Transactional
    public void revokeParentLinkByClassTeacher(Authentication auth, long parentId, long studentId) {
        User teacher = access.currentUser(auth);
        if (teacher.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Только классный руководитель может отзывать связи.");
        }

        // Проверить, что учитель - классный руководитель этого ученика
        Long classId = jdbc.queryForObject(
            "select student_class_id from users where id = ?",
            Long.class, studentId
        );

        if (classId == null) {
            throw new ScheduleException("Ученик не найден.");
        }

        Long teacherClassId = jdbc.queryForObject(
            "select class_id from class_teacher where teacher_user_id = ? and school_id = ?",
            Long.class, teacher.getId(), teacher.getSchool().getId()
        );

        if (teacherClassId == null || !teacherClassId.equals(classId)) {
            throw new AccessDeniedException("Вы не являетесь классным руководителем этого ученика.");
        }

        jdbc.update("delete from parent_student where parent_id = ? and student_id = ?", parentId, studentId);
    }

    public List<Map<String, Object>> getClassParents(long classId) {
        return jdbc.queryForList(
            "select distinct u.id, u.full_name, u.login " +
            "from users u " +
            "join parent_student ps on ps.parent_id = u.id " +
            "join users s on s.id = ps.student_id " +
            "where s.student_class_id = ? and u.role = 'PARENT' " +
            "order by u.full_name, u.login",
            classId
        );
    }

    @Transactional
    public void createAccountByClassTeacher(Authentication auth, String login, String password,
                                           String fullName, Role role) {
        User teacher = access.currentUser(auth);
        if (teacher.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Только классный руководитель может создавать аккаунты.");
        }

        // Проверить, что учитель является классным руководителем
        Long classId = jdbc.queryForObject(
            "select class_id from class_teacher where teacher_user_id = ? and school_id = ?",
            Long.class, teacher.getId(), teacher.getSchool().getId()
        );

        if (classId == null) {
            throw new AccessDeniedException("Вы не назначены классным руководителем.");
        }

        // Классный руководитель может создавать только учеников и родителей
        if (role != Role.STUDENT && role != Role.PARENT) {
            throw new AccessDeniedException("Классный руководитель может создавать только учеников и родителей.");
        }

        long school = teacher.getSchool().getId();

        // Валидация
        if (login == null || !login.matches("[0-9]{1,50}")) {
            throw new ScheduleException("Логин должен быть уникальным номером: от 1 до 50 цифр.");
        }
        if (password == null || password.length() < 8 ||
            password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ScheduleException("Пароль должен содержать минимум 8 символов и не более 72 байт UTF-8.");
        }
        String full = name(fullName, 150);

        catalog.lockSchool(school);

        if (users.findByLogin(login) != null) {
            throw new ScheduleException("Этот номер-логин уже занят.");
        }

        // Создать пользователя
        User created = users.createUser(login, password, role, full, teacher.getSchool());

        // Если это ученик - автоматически назначить в класс руководителя
        if (role == Role.STUDENT) {
            created.setStudentClassId(classId);
            entityManager.flush();
            jdbc.update(
                "update schedule_class set student_count = greatest(student_count, " +
                "(select count(*) from users where student_class_id = ? and role = 'STUDENT')) " +
                "where id = ? and school_id = ?",
                classId, classId, school
            );
        }
    }
}
