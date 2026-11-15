package com.example.edujurnal.academic;

import com.example.edujurnal.entity.*;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.service.*;
import com.example.edujurnal.service.PerformanceAnalyticsService.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import static org.assertj.core.api.Assertions.*;

/** Regression coverage for analytics calculations, filters and date boundaries. */
class PerformanceAnalyticsRegressionTests {
    HikariDataSource source;
    JdbcTemplate jdbc;
    PerformanceAnalyticsService analytics;
    long lessonId = 100;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-11T06:00:00Z"), ZoneId.of("Asia/Bishkek"));
    final Map<String,User> accounts = new HashMap<>();
    Authentication auth(String name) { return new UsernamePasswordAuthenticationToken(name, ""); }
    Filter filter(String from, String to, Interval interval) {
        return new Filter(null,null,null,LocalDate.parse(from),LocalDate.parse(to),null,interval);
    }
    Report report(String from, String to) { return analytics.build(auth("teacher"),filter(from,to,Interval.WEEK)).report(); }

    @BeforeEach void setup() {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:analytics-regression-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword("");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("insert into schedule_school values(1,'School A'),(2,'School B')");
        jdbc.update("insert into schedule_class(id,school_id,name) values(1,1,'7A'),(2,1,'7B'),(3,2,'8A')");
        jdbc.update("insert into schedule_subject values(1,1,'Math'),(2,1,'History'),(3,2,'Other school')");
        jdbc.update("insert into schedule_teacher values(1,1,'Teacher')");
        jdbc.update("insert into schedule_teacher_subject values(1,1,1),(1,1,2)");
        jdbc.update("insert into schedule_room(id,school_id,name) values(1,1,'101')");
        account("teacher",10,Role.TEACHER,1,null);
        account("ordinary",11,Role.TEACHER,1,null);
        account("student",20,Role.STUDENT,1,1L);
        account("classmate",21,Role.STUDENT,1,1L);
        account("otherClass",22,Role.STUDENT,1,2L);
        account("otherSchool",23,Role.STUDENT,2,3L);
        account("parent",30,Role.PARENT,1,null);
        account("director",40,Role.DIRECTOR,1,null);
        jdbc.update("insert into class_teacher values(1,1,10)");
        jdbc.update("insert into parent_student(parent_id,student_id,confirmed_by) values(30,20,40)");
        jdbc.update("insert into academic_year(id,school_id,name,starts_on,ends_on) values(1,1,'2026','2026-09-01','2027-05-31'),(2,1,'2025','2025-09-01','2026-05-31'),(3,2,'Foreign','2026-09-01','2027-05-31')");
        jdbc.update("insert into academic_quarter values(1,1,1,'2026-09-01','2026-11-15'),(2,2,1,'2025-09-01','2025-11-15'),(3,3,1,'2026-09-01','2026-11-15')");
        var access = new ScheduleAccessService(null,null) {
            @Override public User currentUser(Authentication auth) { return accounts.get(auth.getName()); }
        };
        analytics = new PerformanceAnalyticsService(jdbc,access,clock);
    }
    void account(String name,long id,Role role,long school,Long classId) {
        jdbc.update("insert into users(id,login,password,role,school_id,student_class_id,full_name) values(?,?,'audit',?,?,?,?)",id,name,role.name(),school,classId,name);
        User u = new User(); u.setId(id); u.setRole(role); u.setSchool(new School(school,"School")); u.setStudentClassId(classId);
        accounts.put(name,u);
    }
    @AfterEach void close() { source.close(); }
    long lesson(String date, int subject, String time) {
        long id = ++lessonId;
        jdbc.update("insert into schedule_lesson(id,school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token) values(?,1,1,?,1,1,?,?,? ,?)",
                id,subject,LocalDate.parse(date),LocalTime.parse(time),LocalTime.parse(time).plusMinutes(45),UUID.randomUUID().toString());
        return id;
    }
    void grades(String date,int subject,int... values) {
        for (int value:values) jdbc.update("insert into journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value) values(?,20,10,1,?)",lesson(date,subject,"08:00"),value);
    }
    long homework(String mode,int subject) {
        long id = ++lessonId;
        jdbc.update("insert into homework(id,school_id,teacher_id,class_id,subject_id,title,body,due_date,mode,published_at,request_token) values(?,1,1,1,?,'Audit','Body','2026-11-12',?,?,?)",
                id,subject,mode,OffsetDateTime.parse("2026-11-10T10:00:00+06:00"),UUID.randomUUID().toString());
        jdbc.update("insert into homework_audience values(?,20),(?,21)",id,id);
        return id;
    }

    @Test void arithmeticMeanUsesEqualWeightsAndSubjectFilter() {
        grades("2026-11-02",1,4,5,3); grades("2026-11-03",2,2);
        var r = report("2026-11-01","2026-11-11");
        assertThat(r.average().value()).isEqualByComparingTo("3.50");
        assertThat(r.averages().stream().filter(a->a.subjectId()==1).findFirst().orElseThrow().average().value()).isEqualByComparingTo("4.00");
        assertThat(analytics.build(auth("teacher"),new Filter(null,null,null,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-11"),1L,Interval.WEEK)).report().average().count()).isEqualTo(3);
    }
    @Test void detectsAdjacentCompletedWeeklyGrowth() {
        grades("2026-10-26",1,3,3,3); grades("2026-11-02",1,4,4,4);
        var points = report("2026-10-26","2026-11-08").dynamics().getFirst().points();
        assertThat(points.getLast().trend()).isEqualTo(Trend.GROWTH);
        assertThat(points.getLast().change()).isEqualByComparingTo("1.00");
    }
    @Test void detectsMonthlyDropAndAttention() {
        grades("2026-09-10",1,5,5,5); grades("2026-10-10",1,4,4,4);
        var r = analytics.build(auth("teacher"),filter("2026-09-01","2026-10-31",Interval.MONTH)).report();
        assertThat(r.dynamics().getFirst().points().getLast().trend()).isEqualTo(Trend.ATTENTION);
        assertThat(r.attention()).hasSize(1);
        assertThat(r.attention().getFirst().previousFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
    }
    @Test void comparesGrowthBeforeRounding() {
        grades("2026-10-26",1,3,3,3);
        int[] current = new int[101]; Arrays.fill(current,3); Arrays.fill(current,0,50,4);
        grades("2026-11-02",1,current);
        var last = report("2026-10-26","2026-11-08").dynamics().getFirst().points().getLast();
        assertThat(last.average()).isEqualByComparingTo("3.50");
        assertThat(last.trend()).isEqualTo(Trend.STABLE);
    }
    @Test void comparesLowAverageBeforeRounding() {
        int[] values = new int[301]; Arrays.fill(values,3); values[0]=2;
        grades("2026-11-02",1,values);
        var r = report("2026-11-01","2026-11-08");
        assertThat(r.average().value()).isEqualByComparingTo("3.00");
        assertThat(r.attention()).hasSize(1);
    }
    @Test void avoidsTrendForPartialCurrentAndSparseIntervals() {
        grades("2026-11-02",1,5,5,5); grades("2026-11-09",1,2,2,2);
        assertThat(report("2026-11-02","2026-11-11").dynamics().getFirst().points().getLast().trend()).isEqualTo(Trend.NO_DATA);
        grades("2026-10-26",2,5,5); grades("2026-11-02",2,2,2,2);
        var sparse = report("2026-10-26","2026-11-08").dynamics().stream().filter(d->d.subjectId()==2).findFirst().orElseThrow();
        assertThat(sparse.points().getLast().trend()).isEqualTo(Trend.NO_DATA);
    }
    @Test void gapsInDynamicsHaveNoAverageOrTrend() {
        grades("2026-10-19",1,5,5,5); grades("2026-11-02",1,3,3,3);
        var points = report("2026-10-19","2026-11-08").dynamics().getFirst().points();
        assertThat(points.get(1).average()).isNull(); assertThat(points.getLast().trend()).isEqualTo(Trend.NO_DATA);
    }
    @Test void keepsLatestConfirmedAttentionAfterAnEmptyWeek() {
        grades("2026-10-19",1,5,5,5); grades("2026-10-26",1,4,4,4);
        assertThat(report("2026-10-19","2026-11-01").attention()).hasSize(1);
        var extended=report("2026-10-19","2026-11-08");
        assertThat(extended.dynamics().getFirst().points()).anySatisfy(p->assertThat(p.trend()).isEqualTo(Trend.ATTENTION));
        assertThat(extended.attention()).singleElement().satisfies(a -> {
            assertThat(a.previousFrom()).isEqualTo(LocalDate.parse("2026-10-19"));
            assertThat(a.currentFrom()).isEqualTo(LocalDate.parse("2026-10-26"));
            assertThat(a.currentTo()).isEqualTo(LocalDate.parse("2026-11-01"));
            assertThat(a.change()).isEqualByComparingTo("-1.00");
        });
    }
    @Test void attendanceCountsOnlyStartedLessonsAndExcludesUnmarkedFromDenominator() {
        for(String status:List.of("PRESENT","ABSENT","LATE","UNMARKED")) {
            long id=lesson("2026-11-11",1,"08:00");
            if(!status.equals("UNMARKED")) jdbc.update("insert into attendance values(?,20,?,'',1)",id,status);
        }
        lesson("2026-11-11",1,"14:00"); lesson("2026-11-12",1,"08:00");
        var r=analytics.build(auth("teacher"),new Filter(20L,null,null,LocalDate.parse("2026-11-11"),LocalDate.parse("2026-11-12"),null,Interval.WEEK)).report();
        var a=r.attendance().getFirst();
        assertThat(a.present()).isEqualTo(1); assertThat(a.absent()).isEqualTo(1);
        assertThat(a.late()).isEqualTo(1); assertThat(a.unmarked()).isEqualTo(1);
        assertThat(a.absencePercent().value()).isEqualByComparingTo("33.33");
    }
    @Test void homeworkUsesLastVersionAndIncludesPaperWorks() {
        long online=homework("ONLINE",1), paper=homework("NOTEBOOK",1);
        jdbc.update("insert into homework_submission(homework_id,student_id,version,status) values(?,20,1,'RETURNED'),(?,20,2,'ACCEPTED'),(?,21,1,'SENT')",online,online,online);
        jdbc.update("insert into homework_paper_mark(homework_id,student_id,marked_by) values(?,20,10)",paper);
        var h=report("2026-11-10","2026-11-11").homework();
        assertThat(h.accepted()).isEqualTo(2); assertThat(h.notSubmitted()).isEqualTo(1);
        assertThat(h.sent()).isEqualTo(1); assertThat(h.returned()).isZero();
        assertThat(h.acceptedPercent().value()).isEqualByComparingTo("50.00");
    }
    @Test void emptySelectionNeverInventsZeroAveragesOrPercentages() {
        var r=report("2026-11-01","2026-11-11");
        assertThat(r.average().value()).isNull(); assertThat(r.homework().acceptedPercent().value()).isNull();
        assertThat(r.attendance()).allSatisfy(a->assertThat(a.absencePercent().value()).isNull());
    }
    @Test void teacherCannotAccessOtherClassOrSchoolOrForeignSubject() {
        for (long id:List.of(22L,23L)) assertThatThrownBy(()->analytics.build(auth("teacher"),new Filter(id,null,null,null,null,null,Interval.WEEK))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->analytics.build(auth("teacher"),new Filter(null,null,null,null,null,3L,Interval.WEEK))).isInstanceOf(AccessDeniedException.class);
        assertThat(analytics.build(auth("ordinary"),filter("2026-11-01","2026-11-11",Interval.WEEK)).students()).isEmpty();
        assertThat(analytics.build(auth("parent"),filter("2026-11-01","2026-11-11",Interval.WEEK)).students()).extracting(Option::id).containsExactly(20L);
        assertThat(analytics.build(auth("student"),filter("2026-11-01","2026-11-11",Interval.WEEK)).students()).extracting(Option::id).containsExactly(20L);
    }
    @Test void rejectsQuarterFromAnotherYear() {
        assertThat(analytics.build(auth("teacher"),new Filter(null,1L,1L,null,null,null,Interval.WEEK)).quarters()).extracting(Option::id).containsExactly(1L);
        assertThatThrownBy(()->analytics.build(auth("teacher"),new Filter(null,2L,1L,null,null,null,Interval.WEEK)))
                .isInstanceOf(ScheduleException.class).hasMessageContaining("Четверть не относится");
    }
    @Test void clipsWeeklyLabelsToSelectedDatesWithoutCompletingPartialBuckets() {
        grades("2026-11-04",1,4);
        var r=report("2026-11-04","2026-11-05");
        assertThat(r.dynamics().getFirst().points().getFirst().from()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(r.dynamics().getFirst().points().getFirst().to()).isEqualTo(LocalDate.parse("2026-11-05"));
        assertThat(r.dynamics().getFirst().points().getFirst().completed()).isFalse();
        assertThat(r.attendance().getFirst().from()).isEqualTo(r.from());
    }
    @Test void rejectsPeriodsLongerThanOneYear() {
        assertThatThrownBy(() -> report("1900-01-01","2100-12-31"))
                .isInstanceOf(ScheduleException.class).hasMessageContaining("не должен превышать один год");
        assertThatThrownBy(() -> report("2024-01-01","2025-01-01"))
                .isInstanceOf(ScheduleException.class).hasMessageContaining("не должен превышать один год");
    }

    @Test void acceptsLeapYearAndOrdinaryYearAtInclusiveBoundary() {
        for (String year : List.of("2024", "2025")) {
            var r = report(year + "-01-01", year + "-12-31");
            assertThat(r.attendance().size()).isBetween(52, 54);
            assertThat(r.from()).isEqualTo(LocalDate.parse(year + "-01-01"));
            assertThat(r.to()).isEqualTo(LocalDate.parse(year + "-12-31"));
        }
    }

    @Test void rejectsExtremeDatesWithReadableValidationError() {
        for (LocalDate date : List.of(LocalDate.MAX, LocalDate.MIN))
            assertThatThrownBy(() -> analytics.build(auth("teacher"),
                    new Filter(null,null,null,date,date,null,Interval.MONTH)))
                    .isInstanceOf(ScheduleException.class).hasMessageContaining("дат");
    }

    @Test void clipsMonthlyLabelsAndKeepsPartialMonthOutOfTrend() {
        grades("2026-09-15",1,5,5,5); grades("2026-10-15",1,4,4,4);
        var r = analytics.build(auth("teacher"),filter("2026-09-15","2026-10-31",Interval.MONTH)).report();
        var points = r.dynamics().getFirst().points();
        assertThat(points.getFirst().from()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(points.getFirst().to()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(points.getFirst().completed()).isFalse();
        assertThat(points.getLast().trend()).isEqualTo(Trend.NO_DATA);
        assertThat(r.attention()).isEmpty();
    }

    @Test void newerJustifiedGrowthClearsEarlierAttention() {
        grades("2026-10-12",1,5,5,5); grades("2026-10-19",1,4,4,4);
        grades("2026-10-26",1,5,5,5);
        var r = report("2026-10-12","2026-11-08");
        assertThat(r.dynamics().getFirst().points()).anySatisfy(p -> assertThat(p.trend()).isEqualTo(Trend.ATTENTION));
        assertThat(r.attention()).isEmpty();
    }

    @Test void keepsMonthlyAttentionAcrossEmptyMonths() {
        grades("2026-08-10",1,5,5,5); grades("2026-09-10",1,4,4,4);
        var r = analytics.build(auth("teacher"),filter("2026-08-01","2026-10-31",Interval.MONTH)).report();
        assertThat(r.dynamics().getFirst().points().getLast().average()).isNull();
        assertThat(r.attention()).singleElement().satisfies(a ->
                assertThat(a.currentFrom()).isEqualTo(LocalDate.parse("2026-09-01")));
    }
}
