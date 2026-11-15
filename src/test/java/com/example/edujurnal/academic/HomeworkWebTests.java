package com.example.edujurnal.academic;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.repository.SchoolRepository;
import com.example.edujurnal.service.HomeworkService;
import com.example.edujurnal.service.UserService;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** Запускает настоящее приложение и проверяет HTML, CSRF, формы и доступ через HTTP. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:homework-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "logging.level.org.springframework.security=WARN", "logging.level.com.example.edujurnal=INFO"})
@ActiveProfiles("test")
class HomeworkWebTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired SchoolRepository schools;
    @Autowired HomeworkService homework;

    @Test void rendersPagesAndCompletesOnlineAndPaperWorkflows() throws Exception {
        jdbc.update("insert into schedule_school values(1,'Школа тестов')");
        jdbc.update("insert into schedule_class(id,school_id,name) values(1,1,'7А'),(2,1,'7Б')");
        jdbc.update("insert into schedule_subject values(1,1,'Математика')");
        jdbc.update("insert into schedule_teacher values(1,1,'Учитель математики')");
        jdbc.update("insert into schedule_teacher_subject values(1,1,1)");
        jdbc.update("insert into teaching_assignment values(1,1,1,1)");
        var school = schools.findById(1L).orElseThrow();
        long teacherId = users.createUser("hwteacher", "Test123!", Role.TEACHER, "Учитель", school).getId();
        long studentId = users.createUser("hwstudent", "Test123!", Role.STUDENT, "Ученик", school).getId();
        long classmateId = users.createUser("hwclassmate", "Test123!", Role.STUDENT, "Одноклассник", school).getId();
        long outsiderId = users.createUser("hwoutsider", "Test123!", Role.STUDENT, "Другой класс", school).getId();
        users.createUser("hwparent", "Test123!", Role.PARENT, "Родитель", school);
        jdbc.update("update users set teacher_id=1 where id=?", teacherId);
        jdbc.update("update users set student_class_id=1 where id in (?,?)", studentId,classmateId);
        jdbc.update("update users set student_class_id=2 where id=?", outsiderId);
        try (var teacher = login("hwteacher"); var student = login("hwstudent");
             var classmate = login("hwclassmate"); var outsider = login("hwoutsider"); var parent = login("hwparent")) {
            var empty = get(student,"/homework");
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(empty.body()).contains("Заданий пока нет", "href=\"/homework\"", "/css/dashboard.css");
            assertThat(get(student,"/homework/new").statusCode()).isEqualTo(403);
            assertThat(get(parent,"/homework").statusCode()).isEqualTo(403);

            var form = get(teacher,"/homework/new");
            assertThat(form.statusCode()).isEqualTo(200);
            assertThat(form.body()).contains("Математика", "7А", "hw-fields");
            var invalid = post(teacher,"/homework",form.body(),Map.of("classId","1", "subjectId","1",
                    "title","Сохранённый заголовок", "body","", "mode","ONLINE", "dueDate","2026-01-01", "token",field(form.body(),"token")));
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("Сохранённый заголовок", "Укажите содержание ДЗ");

            var created = post(teacher,"/homework", form.body(), Map.of("classId","1", "subjectId","1",
                    "title","Уравнения", "body","Решить уравнения <script>alert(1)</script>", "mode","ONLINE",
                    "dueDate","2026-01-01", "token",field(form.body(),"token")));
            assertThat(created.statusCode()).isEqualTo(302);
            String path = URI.create(created.headers().firstValue("location").orElseThrow()).getPath();
            long homeworkId = Long.parseLong(path.substring(path.lastIndexOf('/')+1));
            var initial = get(student,path);
            assertThat(initial.statusCode()).isEqualTo(200);
            assertThat(initial.body()).contains("Отправить ответ", "История версий", "&lt;script&gt;").doesNotContain("<script>alert(1)</script>");
            assertThat(get(outsider,path).statusCode()).isEqualTo(403);
            assertThat(post(outsider,path+"/submit",get(outsider,"/homework").body(),Map.of("text","Чужой ответ")).statusCode()).isEqualTo(403);

            var sent = submitFile(student,path,initial.body());
            assertThat(sent.statusCode()).isEqualTo(302);
            var studentDetail = get(student,path);
            assertThat(studentDetail.statusCode()).isEqualTo(200);
            assertThat(studentDetail.body()).contains("Версия 1", "Отправлено", "answer.png");
            long fileId = jdbc.queryForObject("select id from homework_file",Long.class);
            for (var client : List.of(teacher,student)) {
                var download = get(client,"/homework/files/"+fileId);
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(download.headers().firstValue("content-disposition").orElseThrow()).contains("attachment", "answer.png");
            }
            assertThat(get(classmate,"/homework/files/"+fileId).statusCode()).isEqualTo(403);
            assertThat(get(outsider,"/homework/files/"+fileId).statusCode()).isEqualTo(403);
            var teacherDetail = get(teacher,path);
            assertThat(teacherDetail.statusCode()).isEqualTo(200);
            assertThat(teacherDetail.body()).contains("Принять", "Вернуть на доработку", "answer.png", "Одноклассник");
            assertThat(post(teacher,path+"/check",teacherDetail.body(),Map.of("studentId",Long.toString(studentId),
                    "version","1", "status","RETURNED", "comment","")).statusCode()).isEqualTo(400);
            assertThat(post(teacher,path+"/check",teacherDetail.body(),Map.of("studentId",Long.toString(studentId),
                    "version","1", "status","RETURNED", "comment","Уточните решение")).statusCode()).isEqualTo(302);
            var returned = get(student,path);
            assertThat(returned.body()).contains("На доработке", "Уточните решение");
            assertThat(post(student,path+"/submit",returned.body(),Map.of("text","Исправленное решение")).statusCode()).isEqualTo(302);
            var stale = post(teacher,path+"/check",teacherDetail.body(),Map.of("studentId",Long.toString(studentId),
                    "version","1", "status","ACCEPTED", "comment",""));
            assertThat(stale.statusCode()).isEqualTo(400);
            assertThat(stale.body()).contains("Ученик отправил новую версию");
            assertThat(post(teacher,path+"/check",stale.body(),Map.of("studentId",Long.toString(studentId),
                    "version","2", "status","ACCEPTED", "comment","Верно")).statusCode()).isEqualTo(302);
            var accepted = get(student,path);
            assertThat(accepted.body()).contains("Версия 2", "Версия 1", "Принято", "Уточните решение", "Верно");
            assertThat(get(student,"/homework").body()).contains("Уравнения", "Принято");
            assertThat(get(teacher,"/homework").body()).contains("Отправили: 1", "Принято: 1");
            var failedSubmit = post(student,path+"/submit",accepted.body(),Map.of("text"," "));
            assertThat(failedSubmit.statusCode()).isEqualTo(400);
            assertThat(failedSubmit.body()).contains("Добавьте текст ответа");

            var teacherAuth = new UsernamePasswordAuthenticationToken("hwteacher","",List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
            long paper = homework.create(teacherAuth,1,1,"Работа в тетради","Решить письменно",LocalDate.of(2026,1,1),"NOTEBOOK");
            String paperPath = "/homework/"+paper;
            var paperDetail = get(teacher,paperPath);
            assertThat(paperDetail.statusCode()).isEqualTo(200);
            assertThat(paperDetail.body()).contains("Отметить как сданную");
            assertThat(post(teacher,paperPath+"/paper",paperDetail.body(),Map.of("studentId",Long.toString(studentId))).statusCode()).isEqualTo(302);
            assertThat(get(student,paperPath).body()).contains("Учитель отметил вашу работу как сданную").doesNotContain("multipart/form-data");
            assertThat(get(student,"/student/dashboard").body()).contains("Задания и ответы").doesNotContain("Задания пока недоступны");
            assertThat(jdbc.queryForObject("select count(*) from homework_submission where homework_id=?",Long.class,homeworkId)).isEqualTo(2);
            // Проверяем границу страниц и пустую страницу без потери доступа к списку.
            for (int i=0;i<20;i++) homework.create(teacherAuth,1,1,"Задание "+i,"Текст",LocalDate.now(),"ONLINE");
            assertThat(get(student,"/homework").body()).contains("Далее →");
            assertThat(get(student,"/homework?page=1").body()).contains("Страница 2", "Уравнения");
        }
    }

    private HttpClient login(String login) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var page = get(client,"/login");
        assertThat(post(client,"/perform-login",page.body(),Map.of("login",login,"password","Test123!")).statusCode()).isEqualTo(302);
        return client;
    }
    private HttpResponse<String> get(HttpClient client,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client,String path,String html,Map<String,String> values) throws Exception {
        var data = new java.util.LinkedHashMap<>(values); data.put("_csrf",field(html,"_csrf"));
        String body = data.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String field(String html,String name) {
        var match = Pattern.compile("name=\""+name+"\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(match.find()).as(name+" field").isTrue(); return match.group(1);
    }
    private String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    private HttpResponse<String> submitFile(HttpClient client,String path,String html) throws Exception {
        String boundary = "hw-"+UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        String prefix = "--"+boundary+"\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n"+field(html,"_csrf")
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nПервый ответ"
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"answer.png\"\r\nContent-Type: image/png\r\n\r\n";
        bytes.write(prefix.getBytes(StandardCharsets.UTF_8));
        bytes.write(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1});
        bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path+"/submit"))
                .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
}
