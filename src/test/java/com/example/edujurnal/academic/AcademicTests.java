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
    @Test void yearCreationIsAtomicAndRejectsOverlap() {
        assertThat(academic.quarters(1)).hasSize(4);
        assertThatThrownBy(()->createYear(director(),"Duplicate",List.of("2030-09-01","2030-10-31","2031-01-10","2031-04-01"))).hasMessageContaining("не пересекаться");
        assertThatThrownBy(()->createYear(director(),"Overlap",List.of("2030-09-01","2030-11-01","2031-01-10","2031-04-01"))).hasMessageContaining("пересекается");
        assertThat(academic.years(1)).hasSize(1);
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
                assertThat(management.body()).contains("Создать учётную запись","Подтвердить связь","Учитель А");
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
