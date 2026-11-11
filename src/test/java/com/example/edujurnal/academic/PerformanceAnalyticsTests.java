package com.example.edujurnal.academic;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.repository.SchoolRepository;
import com.example.edujurnal.service.PerformanceAnalyticsService;
import com.example.edujurnal.service.PerformanceAnalyticsService.*;
import com.example.edujurnal.service.UserService;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:analytics-current;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PerformanceAnalyticsTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PerformanceAnalyticsService analytics;
    @Autowired UserService users;
    @Autowired SchoolRepository schools;

    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock analyticsClock() {
            return Clock.fixed(Instant.parse("2026-11-10T19:00:00Z"),ZoneId.of("Asia/Bishkek"));
        }
    }
    private Authentication auth(String login, Role role) {
        return new UsernamePasswordAuthenticationToken(login,"",List.of(new SimpleGrantedAuthority("ROLE_"+role.name())));
    }

    @Test void includesSavedGradesBeforeLessonStartsAndRefreshesAllCharts() {
        jdbc.update("insert into schedule_school values(1,'Школа')");
        jdbc.update("insert into schedule_class(id,school_id,name) values(1,1,'10-А'),(2,1,'11-Б')");
        jdbc.update("insert into schedule_subject values(1,1,'Математика')");
        jdbc.update("insert into schedule_teacher values(10,1,'Учитель')");
        jdbc.update("insert into schedule_room(id,school_id,name,capacity) values(1,1,'101',30)");
        jdbc.update("insert into schedule_teacher_subject values(1,10,1)");
        jdbc.update("insert into teaching_assignment values(1,1,1,10)");
        var school = schools.findById(1L).orElseThrow();
        long teacher = users.createUser("analyticsTeacher","password123",Role.TEACHER,"Учитель",school).getId();
        long ordinary = users.createUser("ordinaryTeacher","password123",Role.TEACHER,"Другой учитель",school).getId();
        jdbc.update("update users set teacher_id=10 where id=?",teacher);
        jdbc.update("insert into class_teacher values(1,1,?)",teacher);
        List<Long> students = new ArrayList<>();
        for (int i=0;i<9;i++) {
            long student = users.createUser("analyticsStudent"+i,"password123",Role.STUDENT,"Ученик "+i,school).getId();
            jdbc.update("update users set student_class_id=? where id=?", i==8 ? 2 : 1,student);
            students.add(student);
        }
        jdbc.update("insert into academic_year(id,school_id,name,starts_on,ends_on) values(1,1,'2026–2027','2026-09-01','2027-05-31')");
        jdbc.update("insert into academic_quarter values(1,1,1,'2026-09-01','2026-11-15')");
        jdbc.update("""
                insert into schedule_lesson(id,school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
                values(1,1,1,1,10,1,'2026-10-31','08:00','08:45','analytics-old'),
                      (2,1,1,1,10,1,'2026-11-11','08:00','08:45','analytics-today')
                """);
        int[] grades = {2,2,5,3,3,4,4,5};
        for (int i=0;i<8;i++) jdbc.update("""
                insert into journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value)
                values(?,?,?,1,?)
                """,i<3 ? 1 : 2,students.get(i),teacher,grades[i]);
        jdbc.update("insert into attendance values(1,?,'ABSENT','',1),(2,?,'ABSENT','',1)",students.getFirst(),students.get(3));
        jdbc.update("""
                insert into homework(id,school_id,teacher_id,class_id,subject_id,title,body,due_date,mode,published_at,request_token)
                values(1,1,10,1,1,'ДЗ','Решить','2026-11-12','ONLINE',?,'analytics-homework')
                """,OffsetDateTime.parse("2026-11-11T00:10:00+06:00"));
        jdbc.update("insert into homework_audience values(1,?),(1,?),(1,?)",students.getFirst(),students.get(1),students.get(2));
        jdbc.update("""
                insert into homework_submission(homework_id,student_id,version,status)
                values(1,?,1,'RETURNED'),(1,?,2,'ACCEPTED')
                """,students.getFirst(),students.getFirst());

        Authentication teacherAuth = auth("analyticsTeacher",Role.TEACHER);
        Filter all = new Filter(null,null,null,null,null,null,Interval.WEEK);
        var page = analytics.build(teacherAuth,all);
        var report = page.report();
        assertThat(page.students()).hasSize(8);
        assertThat(report.average().count()).isEqualTo(8);
        assertThat(report.average().value()).isEqualByComparingTo("3.50");
        var current = report.dynamics().getFirst().points().stream().filter(p -> p.from().equals(LocalDate.of(2026,11,9))).findFirst().orElseThrow();
        assertThat(current.average()).isEqualByComparingTo("3.80");
        assertThat(current.completed()).isFalse();
        assertThat(current.trend()).isEqualTo(Trend.NO_DATA);
        assertThat(report.attendance().stream().mapToLong(Attendance::absent).sum()).isEqualTo(1);
        assertThat(report.homework().accepted()).isEqualTo(1);
        assertThat(report.homework().returned()).isZero();
        assertThat(report.homework().notSubmitted()).isEqualTo(2);
        assertThat(report.homework().acceptedPercent().value()).isEqualByComparingTo("33.33");
        var today = analytics.calculate(teacherAuth,new Filter(null,null,null,LocalDate.of(2026,11,11),LocalDate.of(2026,11,11),null,Interval.WEEK));
        assertThat(today.average().value()).isEqualByComparingTo("3.80");
        assertThat(today.average().count()).isEqualTo(5);

        // Новая сохранённая оценка должна учитываться при следующем запросе без перезапуска.
        jdbc.update("insert into journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value) values(2,?,?,1,1)",students.getFirst(),teacher);
        assertThat(analytics.build(teacherAuth,all).report().average().count()).isEqualTo(9);
        jdbc.update("insert into homework_submission(homework_id,student_id,version,status) values(1,?,3,'SENT')",students.getFirst());
        var freshHomework = analytics.build(teacherAuth,all).report().homework();
        assertThat(freshHomework.accepted()).isZero();
        assertThat(freshHomework.sent()).isEqualTo(1);
        assertThat(analytics.build(auth("ordinaryTeacher",Role.TEACHER),all).students()).isEmpty();
        assertThatThrownBy(() -> analytics.calculate(teacherAuth,new Filter(students.get(8),null,null,null,null,null,Interval.WEEK)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(analytics.calculate(auth("analyticsStudent3",Role.STUDENT),all).average().value()).isEqualByComparingTo(new BigDecimal("3.00"));
    }
}
