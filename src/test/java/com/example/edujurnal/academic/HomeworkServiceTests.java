package com.example.edujurnal.academic;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.School;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.service.HomeworkService;
import com.example.edujurnal.service.ScheduleAccessService;
import com.example.edujurnal.service.SchoolNotificationService;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;

class HomeworkServiceTests {
    private JdbcTemplate jdbc;
    private HomeworkService service;
    private HikariDataSource source;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), ZoneId.of("Asia/Bishkek"));
    private final Map<String, User> users = new HashMap<>();

    @BeforeEach void setup() {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:homework-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        source.setUsername("sa");
        source.setPassword("");
        source.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__manual_schedule.sql")).execute(source);
        jdbc.execute("create table users(id bigint primary key,school_id bigint,student_class_id bigint,role varchar(20),enabled boolean default true)");
        jdbc.execute("create table teaching_assignment(school_id bigint,class_id bigint,subject_id bigint,teacher_id bigint)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20__homework.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V18__school_communications.sql"),
                new ClassPathResource("db/migration/V22__notification_body_text.sql")).execute(source);
        jdbc.update("insert into schedule_school values(1,'А'),(2,'Б')");
        jdbc.update("insert into schedule_class values(1,1,'7А'),(2,1,'7Б'),(3,2,'8А')");
        jdbc.update("insert into schedule_subject values(1,1,'Математика'),(2,2,'Физика')");
        jdbc.update("insert into schedule_teacher values(11,1,'Учитель'),(12,1,'Другой учитель')");
        jdbc.update("insert into teaching_assignment values(1,1,1,11),(1,2,1,12)");
        user("teacher", 10, Role.TEACHER, 1, null, 11L);
        user("otherTeacher", 12, Role.TEACHER, 1, null, 12L);
        user("student", 20, Role.STUDENT, 1, 1L, null);
        user("classmate", 23, Role.STUDENT, 1, 1L, null);
        user("otherClass", 21, Role.STUDENT, 1, 2L, null);
        user("otherSchool", 30, Role.STUDENT, 2, 3L, null);
        user("parent", 40, Role.PARENT, 1, null, null);
        var access = new ScheduleAccessService(null, null) {
            @Override public User currentUser(Authentication auth) { return users.get(auth.getName()); }
        };
        var proxy = new ProxyFactory(new HomeworkService(jdbc, access, clock, new SchoolNotificationService(jdbc)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (HomeworkService) proxy.getProxy();
    }

    @AfterEach void close() { if (source != null) source.close(); }

    private void user(String login, long id, Role role, long school, Long classId, Long teacherId) {
        User user = new User();
        user.setId(id); user.setRole(role); user.setSchool(new School(school, "Школа"));
        user.setStudentClassId(classId); user.setTeacherId(teacherId);
        users.put(login, user);
        jdbc.update("insert into users(id,school_id,student_class_id,role) values(?,?,?,?)", id, school, classId, role.name());
    }

    private Authentication auth(String login) { return new UsernamePasswordAuthenticationToken(login, ""); }
    private long create(String mode) {
        return service.create(auth("teacher"), 1, 1, "Задачи", "Решить задачи", LocalDate.of(2026, 1, 1), mode, UUID.randomUUID().toString());
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private MockMultipartFile png() {
        return new MockMultipartFile("files", "image.png", "untrusted/type",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1});
    }

    @Test void createsWholeClassAudienceAndRetriesWithoutDuplicates() {
        String token = "create-once";
        long id = service.create(auth("teacher"), 1, 1, "Задачи", "Решить", LocalDate.now(clock), "ONLINE", token);
        assertThat(service.create(auth("teacher"), 1, 1, "Задачи", "Решить", LocalDate.now(clock), "ONLINE", token)).isEqualTo(id);
        assertThat(count("homework")).isEqualTo(1);
        assertThat(jdbc.queryForList("select recipient_id from school_notification order by recipient_id", Long.class))
                .containsExactly(20L, 23L);
        assertThat(jdbc.queryForList("select body from school_notification", String.class))
                .containsOnly("Новое домашнее задание: Задачи");
        assertThat(jdbc.queryForList("select link from school_notification", String.class))
                .containsOnly("/homework/" + id);
        assertThat(jdbc.queryForList("select student_id from homework_audience order by student_id", Long.class)).containsExactly(20L, 23L);
        assertThatThrownBy(() -> service.create(auth("teacher"), 2, 1, "ДЗ", "Текст", LocalDate.now(clock), "ONLINE"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(auth("teacher"), 1, 1, "Изменено", "Решить", LocalDate.now(clock), "ONLINE", token))
                .isInstanceOf(ScheduleException.class);
    }

    @Test void preservesVersionsAllowsLateAnswerAndChecksOnlyLatestVersion() {
        long id = create("ONLINE");
        long first = service.submit(auth("student"), id, "Первый ответ", null);
        service.check(auth("teacher"), id, 20, 1, "RETURNED", "Исправить решение");
        long second = service.submit(auth("student"), id, "", new MultipartFile[]{png()});
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForList("select version from homework_submission order by version", Integer.class)).containsExactly(1, 2);
        assertThatThrownBy(() -> service.check(auth("teacher"), id, 20, 1, "ACCEPTED", ""))
                .isInstanceOf(ScheduleException.class);
        service.check(auth("teacher"), id, 20, 2, "ACCEPTED", "Верно");
        assertThat(jdbc.queryForList("select status from homework_submission order by version", String.class)).containsExactly("RETURNED", "ACCEPTED");
        assertThat(jdbc.queryForObject("select content_type from homework_file", String.class)).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("select submitted_at from homework_submission where id=?", OffsetDateTime.class, second).toInstant())
                .isEqualTo(clock.instant());
    }

    @Test void notifiesReviewsWithoutDuplicatesAndKeepsFullComment() {
        long id = create("ONLINE");
        service.submit(auth("student"), id, "Ответ", null);
        String comment = "Я".repeat(2000);
        service.check(auth("teacher"), id, 20, "RETURNED", comment);
        service.check(auth("teacher"), id, 20, "RETURNED", comment);
        assertThat(jdbc.queryForList("select body from school_notification where event_key like 'hw-check:%'", String.class))
                .containsExactly("Работа возвращена на доработку: " + comment);
        service.submit(auth("student"), id, "Исправлено", null);
        service.check(auth("teacher"), id, 20, "ACCEPTED", "");
        service.check(auth("teacher"), id, 20, "ACCEPTED", "");
        assertThat(jdbc.queryForList("select body from school_notification where event_key like 'hw-check:%' order by id", String.class))
                .containsExactly("Работа возвращена на доработку: " + comment, "Работа принята");
        assertThat(jdbc.queryForList("select recipient_id from school_notification where event_key like 'hw-check:%'", Long.class))
                .containsOnly(20L);
        assertThat(jdbc.queryForList("select link from school_notification where event_key like 'hw-check:%'", String.class))
                .containsOnly("/homework/" + id);
    }

    @Test void rejectsForeignStudentsUnassignedTeachersAndWrongModes() {
        long id = create("ONLINE");
        for (String actor : List.of("otherClass", "otherSchool", "parent", "teacher"))
            assertThatThrownBy(() -> service.submit(auth(actor), id, "Ответ", null)).isInstanceOf(AccessDeniedException.class);
        service.submit(auth("student"), id, "Ответ", null);
        assertThatThrownBy(() -> service.check(auth("otherTeacher"), id, 20, "ACCEPTED", ""))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.check(auth("teacher"), id, 21, "ACCEPTED", ""))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.check(auth("teacher"), id, 20, "RETURNED", " "))
                .isInstanceOf(ScheduleException.class);
        assertThatThrownBy(() -> service.markPaper(auth("teacher"), id, 20)).isInstanceOf(ScheduleException.class);
        long paper = create("NOTEBOOK");
        assertThatThrownBy(() -> service.submit(auth("student"), paper, "Ответ", null)).isInstanceOf(ScheduleException.class);
        service.markPaper(auth("teacher"), paper, 20);
        service.markPaper(auth("teacher"), paper, 20);
        assertThat(count("homework_paper_mark")).isEqualTo(1);
        jdbc.update("delete from teaching_assignment where teacher_id=11");
        assertThatThrownBy(() -> service.check(auth("teacher"), id, 20, "ACCEPTED", ""))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void rejectsEmptyOversizedTooManyAndSpoofedFilesWithoutSavingVersion() {
        long id = create("ONLINE");
        assertThatThrownBy(() -> service.submit(auth("student"), id, " ", null)).isInstanceOf(ScheduleException.class);
        assertThatThrownBy(() -> service.submit(auth("student"), id, "Ответ", new MultipartFile[]{
                new MockMultipartFile("files", "fake.pdf", "application/pdf", new byte[]{1, 2, 3})}))
                .isInstanceOf(ScheduleException.class);
        assertThatThrownBy(() -> service.submit(auth("student"), id, "Ответ", new MultipartFile[]{
                new MockMultipartFile("files", "large.png", "image/png", new byte[10 * 1024 * 1024 + 1])}))
                .isInstanceOf(ScheduleException.class);
        MultipartFile[] six = new MultipartFile[6]; Arrays.fill(six, png());
        assertThatThrownBy(() -> service.submit(auth("student"), id, "Ответ", six)).isInstanceOf(ScheduleException.class);
        assertThat(count("homework_submission")).isZero();
        assertThat(count("homework_file")).isZero();
    }

    @Test void acceptsDocxStructureAndRejectsOrdinaryZip() throws Exception {
        long id = create("ONLINE");
        byte[] valid = zip(Map.of("word/document.xml", "<document/>", "[Content_Types].xml", "<Types/>"));
        service.submit(auth("student"), id, "", new MultipartFile[]{new MockMultipartFile("files", "answer.docx", "", valid)});
        byte[] fake = zip(Map.of("data.txt", "test"));
        assertThatThrownBy(() -> service.submit(auth("student"), id, "", new MultipartFile[]{
                new MockMultipartFile("files", "fake.docx", "", fake)})).isInstanceOf(ScheduleException.class);
        assertThat(count("homework_submission")).isEqualTo(1);
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test void serializesConcurrentSubmissions() throws Exception {
        long id = create("ONLINE");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Long> send = () -> { start.await(); return service.submit(auth("student"), id, "Ответ", null); };
            Future<Long> first = pool.submit(send), second = pool.submit(send);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForList("select version from homework_submission order by version", Integer.class)).containsExactly(1, 2);
    }
}
