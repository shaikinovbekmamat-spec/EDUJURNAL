package com.example.edujurnal.academic;

import com.example.edujurnal.entity.*;
import com.example.edujurnal.service.*;
import com.example.edujurnal.repository.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.datasource.url=jdbc:h2:mem:academic-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AcademicTests {
    @Autowired AcademicService academic;
    @Autowired JournalService journal;
    @Autowired FinalGradeService finals;
    @Autowired LessonJournalService lessonJournal;
    @Autowired UserService users;
    @Autowired SchoolRepository schools;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ScheduleService schedule;
    @Autowired StudentDashboardService dashboard;
    @LocalServerPort int port;
    long student,otherStudent,parent,teacher;
    static final LocalDate TODAY=LocalDate.of(2030,10,21);
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock fixedClock() {return Clock.fixed(Instant.parse("2030-10-21T03:00:00Z"),ZoneId.of("Asia/Bishkek"));}
    }
    Authentication auth(String login,Role role) {return new UsernamePasswordAuthenticationToken(login,"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    Authentication director() {return auth("director",Role.DIRECTOR);}
    Authentication teacher() {return auth("teacher",Role.TEACHER);}
    @BeforeEach void setup() {
        for(String table:List.of("journal_final_grade","journal_grade","parent_student","teaching_assignment","academic_quarter","academic_year","users","schedule_lesson","schedule_teacher_subject","schedule_membership","schedule_subgroup","schedule_room","schedule_teacher","schedule_subject","schedule_class","schedule_school")) jdbc.update("delete from "+table);
        jdbc.update("insert into schedule_school values(1,'Школа А'),(2,'Школа Б')");
        jdbc.update("insert into schedule_class(id,school_id,name) values(1,1,'7А'),(2,2,'8Б')");
        jdbc.update("insert into schedule_subject values(1,1,'Математика'),(2,2,'Физика')");
        jdbc.update("insert into schedule_teacher values(1,1,'Учитель А'),(2,2,'Учитель Б')");
        jdbc.update("insert into schedule_room(id,school_id,name) values(1,1,'101'),(2,2,'201')");
        users.createUser("director","Test123!",Role.DIRECTOR,"Директор А",schools.findById(1L).orElseThrow());
        users.createUser("director2","Test123!",Role.DIRECTOR,"Директор Б",schools.findById(2L).orElseThrow());
        users.createUser("owner","Test123!",Role.OWNER,"Владелец",null);
        teacher=users.createUser("teacher","Test123!",Role.TEACHER,"Учитель А",schools.findById(1L).orElseThrow()).getId();
        student=users.createUser("student","Test123!",Role.STUDENT,"Ученик А",schools.findById(1L).orElseThrow()).getId();
        otherStudent=users.createUser("student2","Test123!",Role.STUDENT,"Ученик Б",schools.findById(2L).orElseThrow()).getId();
        parent=users.createUser("parent","Test123!",Role.PARENT,"Родитель",schools.findById(1L).orElseThrow()).getId();
        jdbc.update("update users set teacher_id=1 where id=?",teacher);
        jdbc.update("update users set student_class_id=1 where id=?",student);
        jdbc.update("update users set student_class_id=2 where id=?",otherStudent);
        academic.assign(director(),1,1,1);
        academic.assign(auth("director2",Role.DIRECTOR),2,2,2);
        createYear(director(),"2030–2031",List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01"));
        createYear(auth("director2",Role.DIRECTOR),"2030–2031",List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01"));
        lesson(1,1,TODAY); lesson(2,2,TODAY);
    }
    void createYear(Authentication actor,String name,List<String> starts) {
        academic.createYear(actor,name,LocalDate.of(2030,9,1),LocalDate.of(2031,8,31),starts.stream().map(LocalDate::parse).toList(),List.of("2030-10-31","2030-12-31","2031-03-31","2031-05-31").stream().map(LocalDate::parse).toList());
    }
    void lesson(long id,long school,LocalDate date) {
        jdbc.update("insert into schedule_lesson(id,school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token) values(?,?,?,?,?,?,?,'10:00','10:45',?)",id,school,school,school,school,school,date,UUID.randomUUID().toString());
    }
    @Test void studentDashboardUsesOwnGradesAndRendersBothStates() throws Exception {
        var empty = dashboard.view(auth("student", Role.STUDENT));
        assertThat(empty.average()).isEqualTo("—");
        assertThat(empty.lessons()).singleElement().satisfies(l -> assertThat(l.subject()).isEqualTo("Математика"));
        assertThat(empty.nextLabel()).isEqualTo("СЛЕДУЮЩИЙ УРОК · ЧЕРЕЗ 60 МИН");
        journal.saveGrade(teacher(), 1, student, 5);
        var filled = dashboard.view(auth("student", Role.STUDENT));
        assertThat(filled.average()).isEqualTo("5,00");
        assertThat(filled.points()).singleElement().satisfies(p -> assertThat(p.average()).isEqualTo("5,00"));
        assertThat(dashboard.view(auth("student2", Role.STUDENT)).average()).isEqualTo("—");
        assertThatThrownBy(() -> dashboard.view(teacher())).isInstanceOf(AccessDeniedException.class);
        try (var client = login("student")) {
            var page = get(client, "/student/dashboard");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("5,00", "Математика", "Динамика среднего балла", "grade-5")
                    .doesNotContain("Физика", "Ученик Б");
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/dashboard-preview.html"),
                    page.body().replace("/css/dashboard.css", "classes/static/css/dashboard.css"));
        }
        jdbc.update("update users set student_class_id=null where id=?", otherStudent);
        try (var client = login("student2")) {
            var page = get(client, "/student/dashboard");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Класс не назначен", "Оценок пока нет", "На сегодня уроков нет");
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/dashboard-empty-preview.html"),
                    page.body().replace("/css/dashboard.css", "classes/static/css/dashboard.css"));
        }
    }
    @Test void yearCreationIsAtomicAndRejectsOverlap() {
        assertThat(academic.quarters(1)).hasSize(4);
        assertThatThrownBy(()->createYear(director(),"Duplicate",List.of("2030-09-01","2030-10-31","2031-01-10","2031-04-01"))).hasMessageContaining("не пересекаться");
        assertThatThrownBy(()->createYear(director(),"Overlap",List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01"))).hasMessageContaining("пересекается");
        assertThat(academic.years(1)).hasSize(1);
    }
    @Test void classesCanBeEditedAndOnlyUnusedClassesDeleted() {
        academic.createCatalog(director(),"class","9А",20);
        long id=jdbc.queryForObject("select id from schedule_class where name='9А'",Long.class);
        academic.updateClass(director(),id,"9Б",25);
        assertThat(jdbc.queryForObject("select name from schedule_class where id=?",String.class,id)).isEqualTo("9Б");
        assertThat(jdbc.queryForObject("select student_count from schedule_class where id=?",Integer.class,id)).isEqualTo(25);
        assertThatThrownBy(()->academic.updateClass(director(),id,"7А",25)).hasMessageContaining("уже существует");
        assertThatThrownBy(()->academic.updateClass(director(),id,"9Б",101)).hasMessageContaining("от 0 до 100");
        assertThatThrownBy(()->academic.updateClass(director(),1,"7А",0)).hasMessageContaining("зачисленных");
        assertThatThrownBy(()->academic.updateClass(director(),1,"7А",100)).hasMessageContaining("вместимость");
        assertThatThrownBy(()->academic.deleteClass(director(),1)).hasMessageContaining("ученики");
        assertThatThrownBy(()->academic.updateClass(director(),2,"8А",20)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteClass(director(),2)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteClass(teacher(),id)).isInstanceOf(AccessDeniedException.class);
        academic.assign(director(),id,1,1);
        assertThatThrownBy(()->academic.deleteClass(director(),id)).hasMessageContaining("используется");
        academic.deleteAssignment(director(),id,1,1);
        academic.deleteClass(director(),id);
        assertThat(jdbc.queryForObject("select count(*) from schedule_class where id=?",Long.class,id)).isZero();
    }
    @Test void assignmentsCanBeReplacedAndRemovedWithoutChangingLessonsOrGrades() {
        journal.saveGrade(teacher(),1,student,5);
        jdbc.update("insert into schedule_teacher values(3,1,'Новый учитель')");
        academic.updateAssignment(director(),1,1,1,1,1,3);
        assertThat(academic.assignments(1)).singleElement().satisfies(a->assertThat(a.get("teacher_id")).isEqualTo(3L));
        academic.assign(director(),1,1,1);
        assertThatThrownBy(()->academic.updateAssignment(director(),1,1,3,1,1,1)).hasMessageContaining("уже существует");
        assertThatThrownBy(()->academic.updateAssignment(director(),1,1,3,2,1,3)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteAssignment(director(),2,2,2)).hasMessageContaining("не найдено");
        assertThatThrownBy(()->academic.updateAssignment(teacher(),1,1,3,1,1,1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.deleteAssignment(teacher(),1,1,3)).isInstanceOf(AccessDeniedException.class);
        academic.deleteAssignment(director(),1,1,3);
        academic.deleteAssignment(director(),1,1,1);
        assertThat(academic.assignments(1)).isEmpty();
        assertThat(jdbc.queryForObject("select teacher_id from schedule_lesson where id=1",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select grade_value from journal_grade where lesson_id=1",Integer.class)).isEqualTo(5);
    }
    @Test void classAndAssignmentFormsSubmitWithCsrf() throws Exception {
        academic.createCatalog(director(),"class","9А",20);
        long id=jdbc.queryForObject("select id from schedule_class where name='9А'",Long.class);
        jdbc.update("insert into schedule_teacher values(3,1,'Новый учитель')");
        try(var client=login("director")) {
            var page=get(client,"/management");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Сохранить класс","Удалить класс","Сохранить назначение","Удалить назначение");
            String token="_csrf="+csrf(page.body());
            assertThat(post(client,"/management/classes/"+id+"/edit",token+"&name=9B&studentCount=21").statusCode()).isEqualTo(302);
            assertThat(post(client,"/management/classes/"+id+"/delete","").statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/classes/"+id+"/delete",token).statusCode()).isEqualTo(302);
            String edit="&oldClassId=1&oldSubjectId=1&oldTeacherId=1&classId=1&subjectId=1&teacherId=3";
            assertThat(post(client,"/management/assignments/edit",edit).statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/assignments/edit",token+edit).statusCode()).isEqualTo(302);
            assertThat(post(client,"/management/assignments/delete","classId=1&subjectId=1&teacherId=3").statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/assignments/delete",token+"&classId=1&subjectId=1&teacherId=3").statusCode()).isEqualTo(302);
            assertThat(academic.assignments(1)).isEmpty();
        }
    }
    @Test void accountEditingAndDeletionRespectRolesAndHistory() {
        academic.updateAccount(director(),null,teacher,"teacher","Новое имя","NewSecret123!",null);
        assertThat(users.findByLogin("teacher").getFullName()).isEqualTo("Новое имя");
        assertThat(passwords.matches("NewSecret123!",users.findByLogin("teacher").getPassword())).isTrue();
        assertThat(jdbc.queryForObject("select name from schedule_teacher where id=1",String.class)).isEqualTo("Новое имя");
        assertThatThrownBy(()->academic.updateAccount(director(),null,otherStudent,"999","Чужой","",2L)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteAccount(director(),null,users.findByLogin("director").getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.deleteAccount(auth("owner",Role.OWNER),1L,teacher)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.deleteAccount(teacher(),null,parent)).isInstanceOf(AccessDeniedException.class);
        journal.saveGrade(teacher(),1,student,5);
        assertThatThrownBy(()->academic.deleteAccount(director(),null,student)).hasMessageContaining("оценки");
        academic.linkParent(director(),null,"parent",student);
        academic.deleteAccount(director(),null,parent);
        assertThat(users.findByLogin("parent")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from parent_student",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from journal_grade",Long.class)).isEqualTo(1L);
    }
    @Test void groupedAccountsRenderAndFormsUpdateAndDelete() throws Exception {
        academic.createAccount(director(),null,"91001","Secret123!","Родитель",Role.PARENT,null,null);
        long id=users.findByLogin("91001").getId();
        try(var client=login("director")) {
            var page=get(client,"/management");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("management-header","role-group","Учителя","Ученики","Родители","account-delete","aria-label=\"Удалить пользователя")
                    .doesNotContain("class=\"role-group\" open","Редактировать пользователя","Сохранить пользователя");
            String token="_csrf="+csrf(page.body());
            String edit="&login=91002&fullName=Updated&password=";
            assertThat(post(client,"/management/accounts/"+id+"/edit",edit).statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/accounts/"+id+"/edit",token+edit).statusCode()).isEqualTo(302);
            assertThat(users.findByLogin("91002").getFullName()).isEqualTo("Updated");
            assertThat(post(client,"/management/accounts/"+id+"/delete","").statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/accounts/"+id+"/delete",token).statusCode()).isEqualTo(302);
            assertThat(users.findByLogin("91002")).isNull();
            preview("management",get(client,"/management").body());
        }
        try(var client=login("owner")) {
            var page=get(client,"/management?schoolId=1");
            assertThat(page.statusCode()).isEqualTo(200);
            long directorId=users.findByLogin("director").getId();
            assertThat(post(client,"/management/accounts/"+directorId+"/edit","_csrf="+csrf(page.body())+"&schoolId=1&login=director&fullName=Director").headers().firstValue("location").orElseThrow())
                    .contains("schoolId=1","#accounts");
        }
    }
    String navigationLinks(String html) {
        var menu=java.util.regex.Pattern.compile("<nav aria-label=\"Основная навигация\">(.*?)</nav>",java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(menu.find()).isTrue();
        return menu.group(1).replaceAll(" aria-current=\"page\"","");
    }
    @Test void navigationIsConsistentAcrossEveryRoleAndSchedule() throws Exception {
        academic.linkParent(director(),null,"parent",student);
        for(String login:List.of("director","teacher","student","parent","owner")) try(var client=login(login)) {
            String home=login.equals("owner") ? "/schedule" : "/"+login+"/dashboard";
            var first=get(client,home);
            assertThat(first.statusCode()).as(login+" home").isEqualTo(200);
            String expected=navigationLinks(first.body());
            var paths=new ArrayList<>(List.of("/schedule?schoolId=1&date=2030-10-21"));
            if(!login.equals("owner")) paths.add("/journal");
            if(login.equals("director") || login.equals("owner")) paths.add("/management?schoolId=1");
            if(login.equals("teacher")) paths.addAll(List.of("/journal/today","/journal/lessons/1"));
            if(login.equals("director")) paths.addAll(List.of("/schedule/new?date=2030-10-21","/schedule/1/edit"));
            for(String path:paths) {
                var page=get(client,path);
                assertThat(page.statusCode()).as(login+" "+path).isEqualTo(200);
                assertThat(navigationLinks(page.body())).as(login+" "+path).isEqualTo(expected);
                assertThat(page.body()).contains("/css/navigation.css","app-sidebar");
            }
        }
        academic.assignClassTeacher(director(),1,teacher);
        try(var client=login("teacher")) {
            String expected=navigationLinks(get(client,"/class-teacher/dashboard").body());
            assertThat(expected).contains("Журнал класса","Родители","Учётные записи","Мой журнал");
            for(String path:List.of("/teacher/dashboard","/class-teacher/journal","/class-teacher/parents","/class-teacher/accounts","/journal","/journal/today","/schedule?date=2030-10-21")) {
                var page=get(client,path);
                assertThat(page.statusCode()).as(path).isEqualTo(200);
                assertThat(navigationLinks(page.body())).as(path).isEqualTo(expected);
            }
        }
    }
    @Test void classTeacherCanLinkAndUnlinkParentsFromAccountsOnlyForOwnStudents() throws Exception {
        academic.assignClassTeacher(director(),1,teacher);
        try(var client=login("teacher")) {
            var page=get(client,"/class-teacher/accounts");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Связать родителя с учеником","Подтвердить связь","returnToAccounts");
            String token="_csrf="+csrf(page.body());
            String payload="&returnToAccounts=true&parentLogin=parent&studentId="+student;
            assertThat(post(client,"/class-teacher/parents/link",payload).statusCode()).isEqualTo(403);
            var linked=post(client,"/class-teacher/parents/link",token+payload);
            assertThat(linked.statusCode()).isEqualTo(302);
            assertThat(linked.headers().firstValue("location").orElseThrow()).endsWith("/class-teacher/accounts#parent-links");
            assertThat(jdbc.queryForObject("select count(*) from parent_student where student_id=? and parent_id=?",Long.class,student,parent)).isEqualTo(1L);
            assertThat(get(client,"/class-teacher/accounts").body()).contains("Отозвать связь","Родитель");
            post(client,"/class-teacher/parents/link",token+"&returnToAccounts=true&parentLogin=parent&studentId="+otherStudent);
            assertThat(jdbc.queryForObject("select count(*) from parent_student where student_id=?",Long.class,otherStudent)).isZero();
            var revoked=post(client,"/class-teacher/parents/revoke",token+"&returnToAccounts=true&parentId="+parent+"&studentId="+student);
            assertThat(revoked.headers().firstValue("location").orElseThrow()).endsWith("/class-teacher/accounts#parent-links");
            assertThat(jdbc.queryForObject("select count(*) from parent_student",Long.class)).isZero();
        }
    }
    @Test void catalogsAndAssignmentsAreSchoolScopedAndIdempotent() {
        academic.createCatalog(director(),"class","9А",25);
        academic.createCatalog(director(),"subject","Химия",0);
        assertThatThrownBy(()->academic.createCatalog(director(),"subject","химия",0)).hasMessageContaining("уже существует");
        academic.assign(director(),1,1,1);
        assertThat(academic.assignments(1)).hasSize(1);
        assertThatThrownBy(()->academic.assign(director(),2,1,1)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.createCatalog(teacher(),"class","8А",0)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void classTeacherAssignmentIsSchoolScopedAndDoesNotChangeAccounts() {
        academic.assignClassTeacher(director(),1,teacher);
        academic.assignClassTeacher(director(),1,teacher);
        assertThat(academic.classTeachers(director(),null)).singleElement().satisfies(c -> {
            assertThat(c.get("teacher_user_id")).isEqualTo(teacher);
            assertThat(c.get("teacher_name")).isEqualTo("Учитель А");
        });
        assertThatThrownBy(()->academic.assignClassTeacher(teacher(),1,teacher)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.assignClassTeacher(auth("owner",Role.OWNER),1,teacher)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.assignClassTeacher(director(),2,teacher)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.assignClassTeacher(director(),1,student)).hasMessageContaining("активного учителя");
        long outsider=users.createUser("foreignTeacher","Test123!",Role.TEACHER,"Чужой учитель",schools.findById(2L).orElseThrow()).getId();
        assertThatThrownBy(()->academic.assignClassTeacher(director(),1,outsider)).hasMessageContaining("активного учителя");
        long replacement=users.createUser("replacementTeacher","Test123!",Role.TEACHER,"Новый руководитель",schools.findById(1L).orElseThrow()).getId();
        jdbc.update("update users set enabled=false where id=?",replacement);
        assertThatThrownBy(()->academic.assignClassTeacher(director(),1,replacement)).hasMessageContaining("активного учителя");
        assertThat(academic.eligibleClassTeachers(director(),null)).singleElement().satisfies(t->assertThat(t.get("id")).isEqualTo(teacher));
        assertThat(jdbc.queryForObject("select teacher_user_id from class_teacher where class_id=1",Long.class)).isEqualTo(teacher);
        jdbc.update("update users set enabled=true where id=?",replacement);
        academic.assignClassTeacher(director(),1,replacement);
        assertThat(jdbc.queryForObject("select teacher_user_id from class_teacher where class_id=1",Long.class)).isEqualTo(replacement);
        assertThat(users.findByLogin("teacher").getRole()).isEqualTo(Role.TEACHER);
        assertThat(users.findByLogin("teacher").getTeacherId()).isEqualTo(1L);
        academic.assignClassTeacher(director(),1,null);
        academic.assignClassTeacher(director(),1,null);
        assertThat(jdbc.queryForObject("select count(*) from class_teacher",Long.class)).isZero();
    }
    @Test void classTeacherFormsAssignReplaceAndRemoveWithCsrf() throws Exception {
        try(var client=login("director")) {
            var page=get(client,"/management");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Классные руководители","name=\"teacherUserId\"","Не назначен");
            String token=csrf(page.body());
            String payload="_csrf="+token+"&classId=1&teacherUserId="+teacher;
            assertThat(post(client,"/management/class-teachers","classId=1&teacherUserId="+teacher).statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/class-teachers",payload).statusCode()).isEqualTo(302);
            var assigned=get(client,"/management");
            assertThat(assigned.statusCode()).isEqualTo(200);
            assertThat(assigned.body()).contains("Учитель А · teacher");
            assertThat(post(client,"/management/class-teachers",payload.replace("classId=1","classId=2")).statusCode()).isEqualTo(400);
            jdbc.update("update users set enabled=false where id=?",teacher);
            var disabled=get(client,"/management");
            assertThat(disabled.statusCode()).isEqualTo(200);
            assertThat(disabled.body()).contains("аккаунт отключён");
            assertThat(post(client,"/management/class-teachers","_csrf="+token+"&classId=1&teacherUserId=").statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject("select count(*) from class_teacher",Long.class)).isZero();
            jdbc.update("update users set enabled=true where id=?",teacher);
        }
        try(var client=login("teacher")) {
            String token=csrf(get(client,"/journal").body());
            assertThat(post(client,"/management/class-teachers","_csrf="+token+"&classId=1&teacherUserId="+teacher).statusCode()).isEqualTo(403);
        }
    }
    @Test void subjectEditingAndDeletionPreserveUsedRecordsAndSchoolScope() {
        academic.createCatalog(director(),"subject","Химия",0);
        long id=jdbc.queryForObject("select id from schedule_subject where name='Химия'",Long.class);
        academic.updateSubject(director(),id,"Химия и экология");
        assertThat(jdbc.queryForObject("select name from schedule_subject where id=?",String.class,id)).isEqualTo("Химия и экология");
        assertThatThrownBy(()->academic.updateSubject(director(),id,"математика")).hasMessageContaining("уже существует");
        assertThatThrownBy(()->academic.updateSubject(director(),2,"Чужой предмет")).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteSubject(director(),2)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteSubject(teacher(),id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.deleteSubject(director(),1)).hasMessageContaining("используется");
        academic.deleteSubject(director(),id);
        assertThat(jdbc.queryForObject("select count(*) from schedule_subject where id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from schedule_lesson where id=1",Long.class)).isEqualTo(1);
    }
    @Test void yearEditingRetainsQuarterIdsAndProtectsGrades() {
        long id=((Number)academic.years(1).getFirst().get("id")).longValue();
        var starts=List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01").stream().map(LocalDate::parse).toList();
        var ends=List.of("2030-10-31","2030-12-31","2031-03-31","2031-05-31").stream().map(LocalDate::parse).toList();
        var quarterIds=academic.quarters(1).stream().map(q->q.get("id")).toList();
        academic.updateYear(director(),id,"Учебный год 2030",LocalDate.of(2030,9,1),LocalDate.of(2031,8,31),starts,ends);
        assertThat(academic.quarters(1).stream().map(q->q.get("id")).toList()).isEqualTo(quarterIds);
        assertThatThrownBy(()->academic.deleteYear(director(),id)).hasMessageContaining("есть уроки");
        journal.saveGrade(teacher(),1,student,5);
        academic.updateYear(director(),id,"2030–2031",LocalDate.of(2030,9,1),LocalDate.of(2031,8,31),starts,ends);
        assertThatThrownBy(()->academic.updateYear(director(),id,"Изменённый",LocalDate.of(2030,8,1),LocalDate.of(2031,8,31),starts,ends)).hasMessageContaining("сохранённые оценки");
        assertThatThrownBy(()->academic.deleteYear(director(),id)).hasMessageContaining("содержит оценки");
        assertThatThrownBy(()->academic.deleteYear(auth("director2",Role.DIRECTOR),id)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.deleteYear(teacher(),id)).isInstanceOf(AccessDeniedException.class);
        assertThat(academic.years(1).getFirst().get("name")).isEqualTo("2030–2031");
    }
    @Test void managementFormsEditAndDeleteUnusedRecordsWithCsrf() throws Exception {
        try(var client=login("director")) {
            var page=get(client,"/management");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Управление пользователями","Сохранить предмет","Удалить предмет","Сохранить учебный год","Удалить учебный год")
                    .doesNotContain("Учебные профили существующих аккаунтов","id=\"profiles\"");
            String token=csrf(page.body());
            academic.createCatalog(director(),"subject","Удаляемый",0);
            long subject=jdbc.queryForObject("select id from schedule_subject where name='Удаляемый'",Long.class);
            assertThat(post(client,"/management/subjects/"+subject+"/edit","_csrf="+token+"&name=NewName").statusCode()).isEqualTo(302);
            assertThat(post(client,"/management/subjects/"+subject+"/delete","").statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/subjects/"+subject+"/delete","_csrf="+token).statusCode()).isEqualTo(302);
            var starts=List.of("2032-09-01","2032-11-01","2033-01-10","2033-04-01").stream().map(LocalDate::parse).toList();
            var ends=List.of("2032-10-31","2032-12-31","2033-03-31","2033-05-31").stream().map(LocalDate::parse).toList();
            academic.createYear(director(),"2032–2033",LocalDate.of(2032,9,1),LocalDate.of(2033,8,31),starts,ends);
            long year=((Number)academic.years(1).getFirst().get("id")).longValue();
            String payload="_csrf="+token+"&name=2032-2033&start=2032-09-01&end=2033-08-31"
                    +"&starts=2032-09-01&ends=2032-10-31&starts=2032-11-01&ends=2032-12-31"
                    +"&starts=2033-01-10&ends=2033-03-31&starts=2033-04-01&ends=2033-06-01";
            assertThat(post(client,"/management/years/"+year+"/edit",payload).statusCode()).isEqualTo(302);
            assertThat(post(client,"/management/years/"+year+"/edit",payload.replace("ends=2032-10-31","ends=2032-11-02")).statusCode()).isEqualTo(400);
            assertThat(post(client,"/management/years/"+year+"/delete","").statusCode()).isEqualTo(403);
            assertThat(post(client,"/management/years/"+year+"/delete","_csrf="+token).statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject("select count(*) from academic_quarter where year_id=?",Long.class,year)).isZero();
        }
    }
    @Test void accountsHaveEncodedPasswordsAndValidProfiles() {
        academic.createAccount(director(),null,"100001","Secret123!","Новый ученик",Role.STUDENT,1L,null);
        var saved=users.findByLogin("100001");
        assertThat(saved.getStudentClassId()).isEqualTo(1L);
        assertThat(passwords.matches("Secret123!",saved.getPassword())).isTrue();
        assertThat(saved.getPassword()).doesNotContain("Secret123!");
        academic.createAccount(director(),null,"100002","Secret123!","Новый учитель",Role.TEACHER,null,null);
        assertThat(users.findByLogin("100002").getTeacherId()).isNotNull();
        assertThatThrownBy(()->academic.createAccount(director(),null,"100003","Secret123!","Чужой ученик",Role.STUDENT,2L,null)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.createAccount(director(),null,"100004","Secret123!","Администратор",Role.OWNER,null,null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.createAccount(director(),null,"abc","Secret123!","Ученик",Role.STUDENT,1L,null)).hasMessageContaining("цифр");
        academic.createAccount(auth("owner",Role.OWNER),2L,"100005","Secret123!","Директор",Role.DIRECTOR,null,null);
        assertThat(users.findByLogin("100005").getSchool().getId()).isEqualTo(2L);
    }
    @Test void savesOnceAndRejectsChangesEvenSameDay() {
        journal.saveGrade(teacher(),1,student,5);
        journal.saveGrade(teacher(),1,student,5);
        assertThatThrownBy(()->journal.saveGrade(teacher(),1,student,4)).hasMessageContaining("Изменение и отмена запрещены");
        assertThat(jdbc.queryForObject("select count(*) from journal_grade",Long.class)).isEqualTo(1);
        assertThat(journal.view(auth("student",Role.STUDENT),false,null,null,null).entries()).singleElement().satisfies(e->assertThat(e.grade()).isEqualTo(5));
        assertThat(schedule.editable(schedule.get(1,1))).isFalse();
    }
    @Test void rejectsPastFutureOutOfRangeAndUnassignedGrades() {
        assertThatThrownBy(()->journal.saveGrade(teacher(),1,student,6)).hasMessageContaining("от 1 до 5");
        assertThatThrownBy(()->journal.saveGrade(teacher(),1,otherStudent,5)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->journal.saveGrade(teacher(),2,otherStudent,5)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->journal.saveGrade(director(),1,student,5)).isInstanceOf(AccessDeniedException.class);
        lesson(3,1,TODAY.minusDays(1)); lesson(4,1,TODAY.plusDays(1));
        assertThatThrownBy(()->journal.saveGrade(teacher(),3,student,5)).hasMessageContaining("текущего дня");
        assertThatThrownBy(()->journal.saveGrade(teacher(),4,student,5)).hasMessageContaining("текущего дня");
        jdbc.update("delete from teaching_assignment where school_id=1");
        assertThatThrownBy(()->journal.saveGrade(teacher(),1,student,5)).isInstanceOf(AccessDeniedException.class);
        assertThat(journal.view(teacher(),false,null,null,null).entries()).isEmpty();
    }
    @Test void subgroupMembershipIsRequired() {
        jdbc.update("insert into schedule_subgroup values(1,1,1,'Подгруппа')");
        jdbc.update("update schedule_lesson set subgroup_id=1 where id=1");
        assertThatThrownBy(()->journal.saveGrade(teacher(),1,student,5)).hasMessageContaining("подгруппу");
        jdbc.update("insert into schedule_membership values(?,?,?,null)",1,student,TODAY);
        journal.saveGrade(teacher(),1,student,5);
    }
    @Test void parentMaySeeChildrenAcrossSchoolsAndRevocationIsImmediate() {
        academic.linkParent(director(),null,"parent",student);
        academic.linkParent(auth("director2",Role.DIRECTOR),null,"parent",otherStudent);
        var auth=auth("parent",Role.PARENT);
        assertThat(journal.view(auth,false,otherStudent,null,null).entries()).singleElement().satisfies(e->assertThat(e.studentId()).isEqualTo(otherStudent));
        assertThat(journal.view(auth,false,null,null,null).children()).hasSize(2);
        academic.unlinkParent(auth("director2",Role.DIRECTOR),parent,otherStudent);
        assertThatThrownBy(()->journal.view(auth,false,otherStudent,null,null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.linkParent(director(),null,"parent",otherStudent)).hasMessageContaining("не принадлежит");
    }
    @Test void teacherCanBeParentWithoutExtraTeachingRights() {
        academic.linkParent(auth("director2",Role.DIRECTOR),null,"teacher",otherStudent);
        assertThat(journal.view(teacher(),true,otherStudent,null,null).entries()).singleElement().satisfies(e->assertThat(e.editable()).isFalse());
        assertThat(journal.view(teacher(),false,null,null,null).entries()).allSatisfy(e->assertThat(e.lessonId()).isEqualTo(1));
    }
    @Test void familyScheduleIsRestrictedToLinkedChildren() throws Exception {
        try (var client=login("parent")) {
            assertThat(get(client,"/schedule").body()).contains("Подтверждённых связей с детьми пока нет");
            assertThat(get(client,"/schedule?childId="+student).statusCode()).isEqualTo(403);
        }
        for (String login : List.of("parent", "teacher")) {
            academic.linkParent(auth("director2",Role.DIRECTOR),null,login,otherStudent);
            try (var client=login(login)) {
                String path="/schedule?family=true&childId="+otherStudent;
                assertThat(get(client,path).statusCode()).isEqualTo(200);
                assertThat(get(client,path).body()).contains("Оценки ребёнка").doesNotContain("Все классы");
                assertThat(get(client,path+"&schoolId=1").statusCode()).isEqualTo(403);
                assertThat(get(client,path+"&classId=1").statusCode()).isEqualTo(403);
                assertThat(get(client,"/schedule?family=true&childId="+student).statusCode()).isEqualTo(403);
                if (login.equals("teacher")) assertThat(get(client,"/schedule?onlyMine=true").statusCode()).isEqualTo(200);
                academic.unlinkParent(auth("director2",Role.DIRECTOR), login.equals("parent")?parent:teacher,otherStudent);
                assertThat(get(client,path).statusCode()).isEqualTo(403);
            }
        }
    }
    @Test void studentsAndDirectorsCannotReadOtherSchoolsOrStudents() {
        assertThatThrownBy(()->journal.view(auth("student",Role.STUDENT),false,otherStudent,null,null)).isInstanceOf(AccessDeniedException.class);
        long foreignYear=((Number)academic.years(2).getFirst().get("id")).longValue();
        assertThatThrownBy(()->journal.view(director(),false,null,foreignYear,null)).isInstanceOf(AccessDeniedException.class);
        assertThat(journal.view(director(),false,null,null,null).entries()).singleElement().satisfies(e->assertThat(e.studentId()).isEqualTo(student));
    }
    @Test void concurrentSubmissionsPreserveExactlyOneGrade() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            List<Future<Boolean>> results=new ArrayList<>();
            for(int value:List.of(4,5)) results.add(pool.submit(()->{ready.countDown();start.await();try{journal.saveGrade(teacher(),1,student,value);return true;}catch(com.example.edujurnal.exception.ScheduleException ex){return false;}}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select count(*) from journal_grade",Long.class)).isEqualTo(1);
    }
    HttpResponse<String> get(HttpClient client,String path) throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(HttpClient client,String path,String body) throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    String csrf(String html) {var m=java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);assertThat(m.find()).isTrue();return m.group(1);}
    HttpClient login(String login) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertThat(post(client,"/perform-login","_csrf="+csrf(get(client,"/login").body())+"&login="+login+"&password=Test123!").statusCode()).isEqualTo(302);return client;
    }
    @Test void httpRendersManagementAndAllJournalRoles() throws Exception {
        academic.linkParent(director(),null,"parent",student);
        journal.saveGrade(teacher(),1,student,5);
        for(String login:List.of("director","teacher","student","parent")) try(var client=login(login)) {
            var page=get(client,"/journal");assertThat(page.statusCode()).as(login+" journal").isEqualTo(200);
            assertThat(page.body()).contains("Математика","1 четверть");
            if(login.equals("student")) preview("journal",page.body());
            if(login.equals("director")) {
                var management=get(client,"/management");assertThat(management.statusCode()).isEqualTo(200);
                assertThat(management.body()).contains("Создать учётную запись","Пользователи по ролям","Учитель А");
                preview("management",management.body());
            } else assertThat(get(client,"/management").statusCode()).isEqualTo(403);
            if(!login.equals("teacher")) assertThat(page.body()).doesNotContain("class=\"grade-form\"");
        }
        try(var owner=login("owner")) {
            assertThat(get(owner,"/management").statusCode()).isEqualTo(200);
            assertThat(get(owner,"/management?schoolId=2").statusCode()).isEqualTo(200);
            assertThat(get(owner,"/journal").statusCode()).isEqualTo(403);
        }
    }
    void preview(String name,String html) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/ui-"+name+".html"),html.replace("/css/academic.css","../src/main/resources/static/css/academic.css").replace("/js/accounts.js","../src/main/resources/static/js/accounts.js"));
    }
    @Test void httpPostsGradeAndRejectsMutationAndMissingCsrf() throws Exception {
        try(var client=login("teacher")) {
            String token=csrf(get(client,"/journal").body());
            String payload="_csrf="+token+"&lessonId=1&studentId="+student+"&value=5";
            assertThat(post(client,"/journal/grades",payload).statusCode()).isEqualTo(302);
            assertThat(post(client,"/journal/grades",payload.replace("value=5","value=4")).statusCode()).isEqualTo(400);
            assertThat(post(client,"/journal/grades","lessonId=1&studentId="+student+"&value=5").statusCode()).isEqualTo(403);
            assertThat(get(client,"/journal").body()).contains("grade-5").doesNotContain("action=\"/journal/grades\"");
        }
        try(var client=login("student")) {
            assertThat(post(client,"/journal/grades","_csrf="+csrf(get(client,"/journal").body())+"&lessonId=1&studentId="+student+"&value=5").statusCode()).isEqualTo(403);
        }
    }

    @Test void httpCreatesCatalogAccountAndFourQuarters() throws Exception {
        try(var client=login("director")) {
            String token=csrf(get(client,"/management").body());
            assertThat(post(client,"/management/catalog","_csrf="+token+"&type=class&name=9A&studentCount=20").statusCode()).isEqualTo(302);
            assertThat(post(client,"/management/accounts","_csrf="+token+"&login=70001&password=Secret123!&fullName=NewStudent&role=STUDENT&classId=1&teacherId=").statusCode()).isEqualTo(302);
            assertThat(users.findByLogin("70001").getStudentClassId()).isEqualTo(1L);
            String year="_csrf="+token+"&name=2031-2032&start=2031-09-01&end=2032-08-31"
                    +"&starts=2031-09-01&ends=2031-10-31&starts=2031-11-01&ends=2031-12-31"
                    +"&starts=2032-01-10&ends=2032-03-31&starts=2032-04-01&ends=2032-05-31";
            assertThat(post(client,"/management/years",year).statusCode()).isEqualTo(302);
            assertThat(academic.quarters(1)).hasSize(8);
        }
    }

    @Test void finalGradesRequireManualConfirmationAndAreImmutableAndScoped() {
        long year=((Number)academic.years(1).getFirst().get("id")).longValue();
        long quarter=((Number)academic.quarters(1).getFirst().get("id")).longValue();
        journal.saveGrade(teacher(),1,student,5);
        assertThat(finals.list(teacher(),false,null,year,quarter)).singleElement().satisfies(f->assertThat(f.value()).isNull());
        finals.save(teacher(),student,1,year,quarter,4);
        finals.save(teacher(),student,1,year,null,3);
        finals.save(teacher(),student,1,year,quarter,4);
        assertThatThrownBy(()->finals.save(teacher(),student,1,year,quarter,5)).hasMessageContaining("уже подтверждён");
        assertThatThrownBy(()->finals.save(teacher(),otherStudent,2,year,null,5)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->finals.save(director(),student,1,year,null,5)).isInstanceOf(AccessDeniedException.class);
        assertThat(finals.list(auth("student",Role.STUDENT),false,null,year,quarter)).singleElement().satisfies(f->{assertThat(f.value()).isEqualTo(4);assertThat(f.editable()).isFalse();});
        assertThat(finals.list(auth("student",Role.STUDENT),false,null,year,null)).singleElement().satisfies(f->assertThat(f.value()).isEqualTo(3));
        assertThatThrownBy(()->finals.list(auth("parent",Role.PARENT),true,student,year,null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void lessonWorkflowExplainsMissingSetupThenSavesAndShowsGradeToFamily() throws Exception {
        jdbc.update("delete from academic_quarter where year_id in(select id from academic_year where school_id=1)");
        jdbc.update("delete from academic_year where school_id=1");
        jdbc.update("update users set student_class_id=null where id=?",student);
        try(var teacherClient=login("teacher");var directorClient=login("director")) {
            var chooser=get(teacherClient,"/journal/today");
            assertThat(chooser.statusCode()).isEqualTo(200);
            assertThat(chooser.body()).contains("Математика","не создана учебная четверть","Открыть урок");
            var lesson=get(teacherClient,"/journal/lessons/1");
            assertThat(lesson.statusCode()).isEqualTo(200);
            assertThat(lesson.body()).contains("нет активных аккаунтов учеников").doesNotContain("Сохранить оценку");
            String token=csrf(get(directorClient,"/management").body());
            assertThat(post(directorClient,"/management/profiles","_csrf="+token+"&userId="+student+"&classId=1").statusCode()).isEqualTo(302);
            createYear(director(),"2030–2031",List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01"));
            var ready=get(teacherClient,"/journal/lessons/1");
            assertThat(ready.body()).contains("Ученик А","Сохранить оценку");
            var result=post(teacherClient,"/journal/grades","_csrf="+csrf(ready.body())+"&lessonId=1&studentId="+student+"&value=5&returnToLesson=true");
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.headers().firstValue("Location").orElseThrow()).endsWith("/journal/lessons/1");
            assertThat(get(teacherClient,"/journal/lessons/1").body()).contains("grade-5","Сохранено. Изменение запрещено.").doesNotContain("Сохранить оценку");
        }
        academic.linkParent(director(),null,"parent",student);
        for(String login:List.of("student","parent")) try(var client=login(login)) {
            assertThat(get(client,"/journal").body()).contains("grade-5");
            assertThat(get(client,"/journal/lessons/1").statusCode()).isEqualTo(403);
        }
    }

    @Test void lessonChooserCannotBypassAssignmentsSchoolOrDate() {
        assertThatThrownBy(()->lessonJournal.page(teacher(),2)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->lessonJournal.lessons(director(),TODAY)).isInstanceOf(AccessDeniedException.class);
        lesson(3,1,TODAY.minusDays(1));
        assertThat(lessonJournal.page(teacher(),3).students()).allSatisfy(s->assertThat(s.editable()).isFalse());
        jdbc.update("delete from teaching_assignment where school_id=1");
        assertThat(lessonJournal.lessons(teacher(),TODAY)).singleElement().satisfies(l->assertThat(l.status()).contains("подтвердить назначение"));
        assertThatThrownBy(()->lessonJournal.page(teacher(),1)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void profileChangesRequireDirectorAndPreserveGradedProfiles() {
        assertThatThrownBy(()->academic.configureProfile(teacher(),student,1L,null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->academic.configureProfile(director(),otherStudent,1L,null)).hasMessageContaining("не принадлежит");
        assertThatThrownBy(()->academic.configureProfile(director(),student,2L,null)).hasMessageContaining("не принадлежит");
        jdbc.update("insert into schedule_class(id,school_id,name) values(3,1,'7Б')");
        academic.configureProfile(director(),student,3L,null);
        assertThat(users.findByLogin("student").getStudentClassId()).isEqualTo(3L);
        academic.configureProfile(director(),student,1L,null);
        journal.saveGrade(teacher(),1,student,5);
        assertThatThrownBy(()->academic.configureProfile(director(),student,3L,null)).hasMessageContaining("сохранённые оценки");
        assertThat(users.findByLogin("student").getStudentClassId()).isEqualTo(1L);
    }
}
