package com.example.edujurnal.schedule;

import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.dto.LessonForm;
import com.example.edujurnal.dto.WeekSchedule;
import com.example.edujurnal.util.BellSchedule;

import com.example.edujurnal.entity.Lesson;
import com.example.edujurnal.repository.LessonRepository;
import com.example.edujurnal.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ScheduleTests {
    @Autowired
    ScheduleService service;
    @Autowired
    LessonRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduleCatalog catalog;
    @Autowired com.example.edujurnal.service.ScheduleGenerationService generation;
    @Autowired com.example.edujurnal.service.CurriculumService curriculum;
    @Autowired com.example.edujurnal.service.UserService users;
    @Autowired com.example.edujurnal.repository.SchoolRepository schools;
    @LocalServerPort int port;
    private static final LocalDate DATE = LocalDate.of(2030, 10, 21);

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-10-20T06:00:00Z"), ZoneId.of("Asia/Bishkek"));
        }
    }

    @BeforeEach
    void clearLessons() {
        jdbc.update("delete from schedule_curriculum");
        jdbc.update("delete from schedule_class_weekly_limit");
        jdbc.update("delete from schedule_teacher_weekly_limit");
        jdbc.update("delete from schedule_planning_settings");
        jdbc.update("delete from schedule_teacher_unavailable");
        repository.deleteAll();
        if (jdbc.queryForObject("select count(*) from schedule_school", Long.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("schedule-fixtures.sql"))
                    .execute(jdbc.getDataSource());
        }
        for (var role : com.example.edujurnal.entity.Role.values()) {
            String login = role.name().toLowerCase() + "_test";
            if (users.findByLogin(login) == null) {
                users.createUser(login, "Test123!", role, login,
                        role == com.example.edujurnal.entity.Role.OWNER ? null : schools.findById(1L).orElseThrow());
            }
        }
        if (users.findByLogin("other_director") == null) {
            users.createUser("other_director", "Test123!", com.example.edujurnal.entity.Role.DIRECTOR,
                    "Другой директор", schools.findById(2L).orElseThrow());
        }
        jdbc.update("update users set teacher_id=1 where login='teacher_test'");
        jdbc.update("update users set student_class_id=1 where login='student_test'");
    }

    private LessonForm form(long schoolClass, Long subgroup, long teacher, long room,
                            String start, String end) {
        LessonForm f = new LessonForm();
        f.setClassId(schoolClass);
        f.setSubgroupId(subgroup);
        f.setSubjectId(1L);
        f.setTeacherId(teacher);
        f.setRoomId(room);
        f.setDate(DATE);
        f.setStartTime(LocalTime.parse(start));
        f.setEndTime(LocalTime.parse(end));
        f.setRequestToken(UUID.randomUUID().toString());
        return f;
    }

    private LessonForm standard() {
        return form(1, null, 1, 1, "09:00", "09:45");
    }

    private com.example.edujurnal.dto.GenerationForm generationInput(int count) {
        var input = new com.example.edujurnal.dto.GenerationForm();
        input.setStart(DATE);
        input.setEnd(DATE.plusDays(11));
        var load = input.getLoads().getFirst();
        load.setClassId(1L);
        load.setSubjectId(1L);
        load.setTeacherId(1L);
        load.setWeeklyCount(count);
        return input;
    }

    @Test
    void generatedScheduleChecksEveryDateAndSavesIdempotently() {
        var blocker = form(2, null, 1, 1, "08:00", "08:45");
        blocker.setDate(DATE.plusWeeks(1));
        service.save(1, null, blocker);
        var input = generationInput(10);
        var second = new com.example.edujurnal.dto.GenerationForm.Load();
        second.setClassId(2L);
        second.setSubjectId(1L);
        second.setTeacherId(1L);
        second.setWeeklyCount(10);
        input.getLoads().add(second);
        var plan = generation.preview(1, input);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(plan.entries()).hasSize(20);
        assertThat(plan.entries()).noneMatch(f -> f.getDate().equals(DATE) && f.getLessonNumber() == 1);
        assertThat(generation.save(1, plan)).isEqualTo(40);
        assertThat(generation.save(1, plan)).isEqualTo(40);
        assertThat(repository.count()).isEqualTo(41);
        var validator = new com.example.edujurnal.service.ScheduleValidator(catalog);
        repository.findAll().forEach(l -> assertThat(validator.checkConflicts(
                LessonForm.from(l), l.getId(), service.forDate(1, l.getDate()))).isEmpty());
    }

    @Test
    void generatedSaveRollsBackAllRowsWhenPreviewBecomesStale() {
        var plan = generation.preview(1, generationInput(3));
        var blocker = plan.entries().getLast().copy();
        blocker.setDate(blocker.getDate().plusWeeks(1));
        blocker.setRequestToken(UUID.randomUUID().toString());
        service.save(1, null, blocker);
        assertThatThrownBy(() -> generation.save(1, plan)).isInstanceOf(ScheduleException.class);
        assertThat(repository.count()).isEqualTo(1);
        assertThatThrownBy(() -> generation.save(2, plan)).hasMessageContaining("Нет доступа");
    }

    @Test
    void generationRejectsImpossibleLoadInvalidTeacherAndForeignClass() {
        var input = generationInput(31);
        assertThatThrownBy(() -> generation.preview(1, input)).hasMessageContaining("от 1 до 30");
        input.getLoads().getFirst().setWeeklyCount(1);
        input.getLoads().getFirst().setSubjectId(3L);
        assertThatThrownBy(() -> generation.preview(1, input)).hasMessageContaining("не назначен");
        input.getLoads().getFirst().setClassId(20L);
        assertThatThrownBy(() -> generation.preview(1, input)).hasMessageContaining("Класс не найден");
        var past = generationInput(1);
        past.setStart(DATE.minusDays(1));
        assertThatThrownBy(() -> generation.preview(1, past)).hasMessageContaining("будущий период");
        var crossTerm = generationInput(1);
        crossTerm.setEnd(LocalDate.of(2031, 1, 10));
        assertThatThrownBy(() -> generation.preview(1, crossTerm)).hasMessageContaining("одном учебном полугодии");
        assertThat(repository.count()).isZero();
    }

    @Test
    void generationReportsCapacityAndOccupiedPeriodWithoutWriting() {
        jdbc.update("update schedule_class set student_count=40 where id=1");
        try {
            assertThatThrownBy(() -> generation.preview(1, generationInput(1))).hasMessageContaining("вместимости");
        } finally {
            jdbc.update("update schedule_class set student_count=0 where id=1");
        }
        var blocker = standard();
        blocker.setStartTime(LocalTime.of(7, 0));
        blocker.setEndTime(LocalTime.of(14, 0));
        service.save(1, null, blocker);
        var input = generationInput(1);
        input.setEnd(DATE);
        assertThatThrownBy(() -> generation.preview(1, input)).hasMessageContaining("нет свободного времени");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void generationHttpPreviewSaveValidationAndPermissions() throws Exception {
        try (var client = loggedIn("director_test")) {
            var page = get(client, "/schedule/generate");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Расписание всей школы", "План пока не заполнен").doesNotContain("loads[0].classId");
            assertThat(get(client, "/management").body()).contains("Нагрузка классов и учителей", "Сохранить лимит класса", "Сохранить лимит учителя");
            var classPage = get(client, "/management/curriculum?classId=1");
            assertThat(classPage.statusCode()).isEqualTo(200);
            assertThat(classPage.body()).contains("Часов в неделю", "Сохранить учебный план");
            assertThat(post(client, "/management/curriculum?classId=1", "_csrf=" + csrf(classPage.body())
                    + "&loads[0].subjectId=1&loads[0].teacherId=1&loads[0].weeklyCount=3").statusCode()).isEqualTo(302);
            assertThat(post(client, "/management/copy", "_csrf=" + csrf(page.body())
                    + "&sourceClassId=1&classId=2").statusCode()).isEqualTo(302);
            assertThat(get(client, "/management").body()).contains("6 ч. в неделю", "3 ч. в неделю");
            assertThat(curriculum.teacherSummary(1)).anyMatch(t -> t.id() == 1 && t.hours() == 6);
            String body = "_csrf=" + csrf(page.body()) + "&start=" + DATE + "&end=" + DATE.plusDays(11)
                    + "&loads[0].classId=1&loads[0].subjectId=1&loads[0].teacherId=1&loads[0].weeklyCount=3";
            var preview = post(client, "/schedule/generate/preview", body);
            assertThat(preview.statusCode()).isEqualTo(302);
            preview = get(client, "/schedule/generate/draft");
            assertThat(preview.statusCode()).isEqualTo(200);
            assertThat(preview.body()).contains("Черновик расписания школы", "12 занятий за период", "7А класс", "7Б класс");
            assertThat(get(client, "/schedule/generate/draft?view=teacher").statusCode()).isEqualTo(200);
            assertThat(repository.count()).isZero();
            var token = java.util.regex.Pattern.compile("name=\"previewToken\" value=\"([^\"]+)\"").matcher(preview.body());
            assertThat(token.find()).isTrue();
            String save = "_csrf=" + csrf(preview.body()) + "&previewToken=" + token.group(1) + "&confirmReplacement=true";
            assertThat(post(client, "/schedule/generate/save", save).statusCode()).isEqualTo(302);
            assertThat(post(client, "/schedule/generate/save", save).statusCode()).isEqualTo(302);
            assertThat(repository.count()).isEqualTo(12);
            assertThat(post(client, "/schedule/generate/preview", body + "&schoolId=2").statusCode()).isEqualTo(403);
            assertThat(post(client, "/schedule/generate/preview", "start=" + DATE).statusCode()).isEqualTo(403);
            var invalid = post(client, "/schedule/generate/preview", body.replace("start=" + DATE, "start=2030-10-19"));
            assertThat(invalid.statusCode()).isEqualTo(200);
            assertThat(invalid.body()).contains("будущий период");
        }
        try (var client = loggedIn("teacher_test")) {
            assertThat(get(client, "/schedule/generate").statusCode()).isEqualTo(403);
            assertThat(get(client, "/management/curriculum?classId=1").statusCode()).isEqualTo(403);
            assertThat(post(client, "/schedule/generate/preview", "_csrf=" + csrf(get(client, "/login").body())).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void workingDaysReplaceOldRestrictionsAndConstrainTheWholeDay() {
        schoolCurriculum(3);
        curriculum.setUnavailable(1, 1, 1, 1, false);
        curriculum.saveWorkingDays(1, 1, java.util.List.of(1, 3, 5));
        assertThat(curriculum.workingDays(1).get(1L)).containsExactly(1, 3, 5);
        assertThat(curriculum.unavailable(1)).hasSize(12);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        assertThat(plan.entries()).allMatch(e -> java.util.List.of(1, 3, 5).contains(e.getDate().getDayOfWeek().getValue()));
        assertThatThrownBy(() -> generation.move(plan, plan.entries().getFirst().getRequestToken(), 2, 1, 1))
                .hasMessageContaining("недоступен");
        curriculum.saveWorkingDays(1, 1, java.util.List.of(1, 2, 3, 4, 5));
        assertThat(curriculum.unavailable(1)).isEmpty();
        assertThatThrownBy(() -> generation.save(1, plan)).hasMessageContaining("изменились");
        assertThatThrownBy(() -> curriculum.saveWorkingDays(1, 1, java.util.List.of())).hasMessageContaining("хотя бы один");
        assertThatThrownBy(() -> curriculum.saveWorkingDays(1, 1, java.util.List.of(6))).hasMessageContaining("рабочий день");
        assertThatThrownBy(() -> curriculum.saveWorkingDays(2, 1, java.util.List.of(1))).hasMessageContaining("не найден");
        assertThat(curriculum.unavailable(1)).isEmpty();
    }

    @Test
    void tooMuchLoadForWorkingDaysIsReportedBeforeSearch() {
        schoolCurriculum(4);
        curriculum.saveWorkingDays(1, 1, java.util.List.of(1));
        assertThatThrownBy(() -> generation.previewSchool(1, DATE, DATE.plusDays(11)))
                .hasMessageContaining("назначено 8 ч., доступно 6");
    }

    @Test
    void workingDaysHttpSavesSeveralDaysAndRejectsEmptySelection() throws Exception {
        try (var client = loggedIn("director_test")) {
            var page = get(client, "/schedule/generate");
            assertThat(page.body()).contains("Рабочие дни учителя", "name=\"days\"").doesNotContain("/schedule/generate/unavailable");
            String token = "_csrf=" + csrf(page.body());
            assertThat(post(client, "/schedule/generate/working-days", token + "&teacherId=1&days=1&days=3").statusCode()).isEqualTo(302);
            assertThat(curriculum.workingDays(1).get(1L)).containsExactly(1, 3);
            assertThat(get(client, "/schedule/generate").body()).contains("Рабочие дни учителя сохранены");
            assertThat(post(client, "/schedule/generate/working-days", token + "&teacherId=1").statusCode()).isEqualTo(302);
            assertThat(curriculum.workingDays(1).get(1L)).containsExactly(1, 3);
            assertThat(get(client, "/schedule/generate").body()).contains("Выберите хотя бы один рабочий день");
            assertThat(post(client, "/schedule/generate/working-days", token + "&teacherId=1&days=1&schoolId=2").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void workloadRequiresExplicitHoursAndStartsEmpty() {
        assertThat(curriculum.loads(1)).isEmpty();
        assertThat(curriculum.classForm(1, 1).getLoads()).allMatch(l -> l.getWeeklyCount() == 0);
        assertThat(curriculum.teacherSummary(1)).allMatch(t -> t.hours() == 0);
        var input = generationInput(3);
        var row = new com.example.edujurnal.dto.GenerationForm.Load();
        row.setSubjectId(1L);
        row.setTeacherId(1L);
        input.setLoads(java.util.List.of(row));
        assertThatThrownBy(() -> curriculum.saveClass(1, 1, input)).isInstanceOf(ScheduleException.class);
        assertThat(curriculum.loads(1)).isEmpty();
    }

    @Test
    void weeklyLimitsAreIndependentAndInvalidateDrafts() {
        schoolCurriculum(3);
        curriculum.saveWeeklyLimit(1, "class", 1, 3);
        curriculum.saveWeeklyLimit(1, "class", 2, 20);
        curriculum.saveWeeklyLimit(1, "teacher", 1, 6);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        assertThat(plan.entries()).hasSize(6);
        curriculum.saveWeeklyLimit(1, "teacher", 1, 5);
        assertThatThrownBy(() -> generation.previewSchool(1, DATE, DATE.plusDays(11)))
                .hasMessageContaining("назначено 6 уроков, недельный лимит — 5");
        assertThatThrownBy(() -> generation.save(1, plan)).hasMessageContaining("изменились");
        curriculum.saveWeeklyLimit(1, "teacher", 1, 30);
        curriculum.saveWeeklyLimit(1, "class", 1, 2);
        assertThatThrownBy(() -> generation.previewSchool(1, DATE, DATE.plusDays(11)))
                .hasMessageContaining("в плане 3 уроков, недельный лимит — 2");
        assertThatThrownBy(() -> generation.preview(1, generationInput(3))).hasMessageContaining("лимит класса");
        assertThat(curriculum.classLimits(1)).containsEntry(2L, 20);
        assertThat(curriculum.classLimits(2)).isEmpty();
        assertThatThrownBy(() -> curriculum.saveWeeklyLimit(2, "class", 1, 20)).hasMessageContaining("не найден");
        assertThatThrownBy(() -> curriculum.saveWeeklyLimit(2, "teacher", 1, 20)).hasMessageContaining("не найден");
        assertThatThrownBy(() -> curriculum.saveWeeklyLimit(1, "teacher", 1, 31)).hasMessageContaining("от 0 до 30");
        assertThat(curriculum.teacherLimits(1)).containsEntry(1L, 30);
    }

    @Test
    void zeroClassLimitAllowsAnEmptyPlanAndTeacherLimitAppliesToDirectPreview() {
        curriculum.saveClass(1, 1, generationInput(3));
        curriculum.saveWeeklyLimit(1, "class", 2, 0);
        curriculum.saveWeeklyLimit(1, "teacher", 1, 2);
        assertThatThrownBy(() -> generation.preview(1, generationInput(3))).hasMessageContaining("лимит учителя");
        curriculum.saveWeeklyLimit(1, "teacher", 1, 3);
        assertThat(generation.previewSchool(1, DATE, DATE.plusDays(11)).entries()).hasSize(3);
    }

    @Test
    void directorEditsWeeklyLimitsInManagement() throws Exception {
        try (var client = loggedIn("director_test")) {
            var page = get(client, "/management");
            String token = "_csrf=" + csrf(page.body());
            assertThat(post(client, "/management/workload-limits", token + "&type=class&id=1&weeklyLimit=25").statusCode()).isEqualTo(302);
            assertThat(curriculum.classLimits(1)).containsEntry(1L, 25);
            assertThat(post(client, "/management/workload-limits", token + "&type=teacher&id=1&weeklyLimit=20").statusCode()).isEqualTo(302);
            assertThat(curriculum.teacherLimits(1)).containsEntry(1L, 20);
            assertThat(get(client, "/management").body()).contains("value=\"25\"", "value=\"20\"");
            assertThat(post(client, "/management/workload-limits", token + "&type=teacher&id=1&weeklyLimit=30").statusCode()).isEqualTo(302);
            assertThat(curriculum.teacherLimits(1)).containsEntry(1L, 30);
        }
        for (String login : List.of("teacher_test", "owner_test", "other_director")) {
            try (var client = loggedIn(login)) {
                var page = get(client, "/login");
                post(client, "/management/workload-limits", "_csrf=" + csrf(page.body()) + "&type=teacher&id=1&weeklyLimit=0");
                assertThat(curriculum.teacherLimits(1)).containsEntry(1L, 30);
            }
        }
    }

    private void schoolCurriculum(int hours) {
        curriculum.saveClass(1, 1, generationInput(hours));
        curriculum.copy(1, 1, 2);
    }

    @Test
    void schoolPlanReplacesOnlySelectedPeriodAndRejectsStaleDraft() {
        schoolCurriculum(3);
        var old = service.save(1, null, standard());
        var outside = standard();
        outside.setDate(DATE.plusMonths(1));
        var retained = service.save(1, null, outside);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        assertThat(plan.replaced()).containsKey(old.getId());
        assertThat(repository.count()).isEqualTo(2);
        assertThat(generation.save(1, plan)).isEqualTo(12);
        assertThat(generation.save(1, plan)).isEqualTo(12);
        assertThat(repository.count()).isEqualTo(13);
        assertThat(repository.findById(retained.getId())).isPresent();
        assertThat(repository.findById(old.getId())).isEmpty();
        var stale = generation.previewSchool(1, DATE, DATE.plusDays(11));
        curriculum.saveSettings(1, 5, 5);
        assertThatThrownBy(() -> generation.save(1, stale)).hasMessageContaining("изменились");
        assertThat(repository.count()).isEqualTo(13);
    }

    @Test
    void schoolPreflightAndMovesRespectStoredConstraints() {
        assertThat(curriculum.problems(1)).anyMatch(e -> e.contains("7А"));
        schoolCurriculum(3);
        curriculum.saveSettings(1, 3, 3);
        curriculum.setUnavailable(1, 1, 1, 1, false);
        var form = curriculum.classForm(1, 1);
        form.getLoads().stream().filter(l -> l.getSubjectId() == 1).forEach(l -> l.setRoomId(2L));
        curriculum.saveClass(1, 1, form);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        assertThat(plan.entries()).allSatisfy(e -> {
            assertThat(e.getLessonNumber()).isLessThanOrEqualTo(3);
            assertThat(e.getDate().getDayOfWeek().getValue()).isLessThanOrEqualTo(3);
            if (e.getClassId() == 1) assertThat(e.getRoomId()).isEqualTo(2L);
        });
        var entry = plan.entries().getFirst();
        assertThatThrownBy(() -> generation.move(plan, entry.getRequestToken(), 1, 1, entry.getRoomId())).hasMessageContaining("недоступен");
        assertThatThrownBy(() -> generation.move(plan, entry.getRequestToken(), 5, 6, entry.getRoomId())).hasMessageContaining("за пределами");
        assertThatCode(() -> generation.move(plan, entry.getRequestToken(), entry.getDate().getDayOfWeek().getValue(), entry.getLessonNumber(), entry.getRoomId())).doesNotThrowAnyException();
        curriculum.saveSettings(1, 1, 2);
        assertThat(curriculum.problems(1)).anyMatch(e -> e.contains("назначено 6 ч."));
        assertThatThrownBy(() -> generation.previewSchool(1, DATE, DATE.plusDays(11))).isInstanceOf(ScheduleException.class);
        assertThatThrownBy(() -> curriculum.copy(1, 1, 20)).hasMessageContaining("Класс не найден");
    }

    @Test
    void schoolReplacementPreservesLessonsIfCatalogChanged() {
        schoolCurriculum(2);
        var old = service.save(1, null, standard());
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        jdbc.update("update schedule_class set student_count=100 where id=2");
        try {
            assertThatThrownBy(() -> generation.save(1, plan)).hasMessageContaining("кабинета");
            assertThat(repository.findById(old.getId())).isPresent();
            assertThat(repository.count()).isEqualTo(1);
        } finally { jdbc.update("update schedule_class set student_count=0 where id=2"); }
    }

    @Test
    void pinnedLessonsKeepTheirPlacementDuringSchoolRecalculation() {
        schoolCurriculum(5);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        var entry = plan.entries().getFirst();
        var moved = generation.move(plan, entry.getRequestToken(), 5, 6, 3);
        var pinned = generation.pin(moved, entry.getRequestToken());
        var regenerated = generation.regenerate(pinned);
        assertThat(regenerated.entries()).hasSize(10);
        var fixed = regenerated.entries().stream().filter(e -> e.getRequestToken().equals(entry.getRequestToken())).findFirst().orElseThrow();
        assertThat(fixed.getDate().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(fixed.getLessonNumber()).isEqualTo(6);
        assertThat(fixed.getRoomId()).isEqualTo(3L);
        assertThat(regenerated.pinned()).containsExactly(entry.getRequestToken());
        assertThat(generation.save(1, regenerated)).isEqualTo(20);
    }

    @Test
    void staleSchoolDraftCannotEraseNewManualLesson() {
        schoolCurriculum(3);
        var plan = generation.previewSchool(1, DATE, DATE.plusDays(11));
        var newLesson = service.save(1, null, standard());
        assertThatThrownBy(() -> generation.save(1, plan)).hasMessageContaining("изменились");
        assertThat(repository.findById(newLesson.getId())).isPresent();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void createsAndEditsPersistedLesson() {
        Lesson created = service.save(1, null, standard());
        LessonForm edit = LessonForm.from(created);
        edit.setStartTime(LocalTime.of(10, 0));
        edit.setEndTime(LocalTime.of(10, 45));
        service.save(1, created.getId(), edit);
        assertThat(service.get(1, created.getId()).getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameLessonDoesNotConflictWithItself() {
        Lesson created = service.save(1, null, standard());
        assertThatCode(() -> service.save(1, created.getId(), LessonForm.from(created))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"08:50,09:10", "09:30,10:00", "09:10,09:30", "08:00,10:00", "09:00,09:45"})
    void detectsEveryShapeOfOverlap(String start, String end) {
        service.save(1, null, standard());
        assertThatThrownBy(() -> service.save(1, null, form(1, null, 1, 1, start, end)))
                .isInstanceOf(ScheduleException.class)
                .hasMessageContaining("Учитель").hasMessageContaining("Кабинет").hasMessageContaining("класса");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void independentlyChecksTeacherRoomAndClass() {
        service.save(1, null, standard());
        assertThatThrownBy(() -> service.save(1, null, form(2, null, 1, 2, "09:00", "09:45")))
                .hasMessageContaining("Учитель").hasMessageNotContaining("Кабинет").hasMessageNotContaining("класса");
        assertThatThrownBy(() -> service.save(1, null, form(2, null, 2, 1, "09:00", "09:45")))
                .hasMessageContaining("Кабинет").hasMessageNotContaining("Учитель").hasMessageNotContaining("класса");
        assertThatThrownBy(() -> service.save(1, null, form(1, null, 2, 2, "09:00", "09:45")))
                .hasMessageContaining("класса").hasMessageNotContaining("Учитель").hasMessageNotContaining("Кабинет");
    }

    @Test
    void adjacentLessonsAndOtherDatesAreAllowed() {
        service.save(1, null, standard());
        service.save(1, null, form(1, null, 1, 1, "09:45", "10:30"));
        service.save(1, null, form(1, null, 1, 1, "08:15", "09:00"));
        LessonForm tomorrow = standard();
        tomorrow.setDate(DATE.plusDays(1));
        service.save(1, null, tomorrow);
        assertThat(repository.count()).isEqualTo(4);
    }

    @Test
    void disjointSubgroupsCanHaveSimultaneousLessons() {
        service.save(1, null, form(1, 1L, 1, 1, "09:00", "09:45"));
        service.save(1, null, form(1, 2L, 2, 2, "09:00", "09:45"));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void subgroupConflictsWithWholeClassAndSharedStudents() {
        service.save(1, null, form(1, 1L, 1, 1, "09:00", "09:45"));
        for (Long subgroup : Arrays.asList(null, 1L, 3L)) {
            assertThatThrownBy(() -> service.save(1, null, form(1, subgroup, 2, 2, "09:00", "09:45")))
                    .hasMessageContaining("класса");
        }
    }

    @Test
    void wholeClassConflictsWithSubgroupInReverseOrder() {
        service.save(1, null, standard());
        assertThatThrownBy(() -> service.save(1, null, form(1, 2L, 2, 2, "09:00", "09:45")))
                .hasMessageContaining("класса");
    }

    @Test
    void expiredMembershipDoesNotCauseConflict() {
        jdbc.update("update schedule_membership set active_to = ? where subgroup_id = 3", DATE.minusDays(1));
        try {
            service.save(1, null, form(1, 1L, 1, 1, "09:00", "09:45"));
            service.save(1, null, form(1, 3L, 2, 2, "09:00", "09:45"));
            assertThat(repository.count()).isEqualTo(2);
        } finally {
            jdbc.update("update schedule_membership set active_to = null where subgroup_id = 3");
        }
    }

    @ParameterizedTest
    @CsvSource({"09:00,09:00", "10:00,09:00"})
    void rejectsInvalidInterval(String start, String end) {
        assertThatThrownBy(() -> service.save(1, null, form(1, null, 1, 1, start, end)))
                .hasMessageContaining("Окончание");
    }

    @Test
    void rejectsMissingAndForeignReferences() {
        LessonForm missing = standard();
        missing.setDate(null);
        assertThatThrownBy(() -> service.save(1, null, missing)).hasMessageContaining("дату");
        assertThatThrownBy(() -> service.save(1, null, form(2, 1L, 1, 1, "09:00", "09:45")))
                .hasMessageContaining("Подгруппа");
        assertThatThrownBy(() -> service.save(1, null, form(1, null, 999, 1, "09:00", "09:45")))
                .hasMessageContaining("Учитель не найден");
        Lesson created = service.save(1, null, standard());
        assertThatThrownBy(() -> service.get(999, created.getId())).hasMessageContaining("не найден");
    }

    @Test
    void invalidEditPreservesOriginalLesson() {
        Lesson first = service.save(1, null, standard());
        Lesson second = service.save(1, null, form(2, null, 2, 2, "09:00", "09:45"));
        LessonForm edit = LessonForm.from(second);
        edit.setTeacherId(first.getTeacherId());
        assertThatThrownBy(() -> service.save(1, second.getId(), edit)).hasMessageContaining("Учитель");
        assertThat(service.get(1, second.getId()).getTeacherId()).isEqualTo(2L);
    }

    @Test
    void rejectsStaleEdit() {
        Lesson saved = service.save(1, null, standard());
        LessonForm edit = LessonForm.from(saved);
        edit.setRoomId(2L);
        service.save(1, saved.getId(), edit);
        edit.setRoomId(3L);
        assertThatThrownBy(() -> service.save(1, saved.getId(), edit)).hasMessageContaining("другой вкладке");
        assertThat(service.get(1, saved.getId()).getRoomId()).isEqualTo(2L);
    }

    @Test
    void repeatedCreateDoesNotDuplicate() {
        LessonForm input = standard();
        Lesson first = service.save(1, null, input);
        Lesson second = service.save(1, null, input);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void usesBishkekTimeAndRejectsStartedLessons() {
        assertThat(service.today()).isEqualTo(LocalDate.of(2030, 10, 20));
        LessonForm past = standard();
        past.setDate(service.today());
        past.setStartTime(LocalTime.NOON);
        past.setEndTime(LocalTime.of(12, 45));
        assertThatThrownBy(() -> service.save(1, null, past)).hasMessageContaining("будущее время");
        Lesson existing = service.save(1, null, standard());
        jdbc.update("update schedule_lesson set lesson_date = ? where id = ?", service.today(), existing.getId());
        LessonForm edit = LessonForm.from(service.get(1, existing.getId()));
        edit.setDate(DATE);
        assertThatThrownBy(() -> service.save(1, existing.getId(), edit)).hasMessageContaining("проведённый");
    }

    @Test
    void concurrentConflictingCreatesCannotBothSucceed() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> {
                ready.countDown();
                go.await();
                try {
                    service.save(1, null, standard());
                    return true;
                } catch (ScheduleException ex) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(action);
            Future<Boolean> b = pool.submit(action);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    private String csrf(String html) {
        var token = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("Spring Security CSRF token").isTrue();
        return token.group(1);
    }

    private HttpClient loggedIn(String login) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
        String token = csrf(get(client, "/login").body());
        var response = post(client, "/perform-login", "_csrf=" + token + "&login=" + login + "&password=Test123!");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).doesNotContain("error");
        return client;
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousRequestsRequireLoginAndStaticAssetsArePublic() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/schedule");
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("location").orElseThrow()).endsWith("/login");
            assertThat(get(client, "/schedule.css").statusCode()).isEqualTo(200);
            assertThat(get(client, "/schedule.js").statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @CsvSource({"teacher_test", "student_test", "parent_test"})
    void schoolMembersCanReadButCannotEdit(String login) throws Exception {
        try (var client = loggedIn(login)) {
            var page = get(client, "/schedule?date=" + DATE);
            assertThat(page.statusCode()).isEqualTo(200);
            if (login.equals("parent_test")) {
                assertThat(page.body()).contains("Подтверждённых связей с детьми пока нет")
                        .doesNotContain("7А класс", "9В класс");
            } else {
                assertThat(page.body()).contains("Школа тестов", "7А класс")
                        .doesNotContain("+ Добавить", "Другая школа", "9В класс");
            }
            String dashboard = "/" + login.replace("_test", "") + "/dashboard";
            var account = get(client, dashboard);
            assertThat(account.statusCode()).isEqualTo(200);
            assertThat(account.body()).contains(login, "Расписание школы");
            assertThat(get(client, "/schedule/new").statusCode()).isEqualTo(403);
            assertThat(get(client, "/schedule/123/edit").statusCode()).isEqualTo(403);
            String token = csrf(get(client, "/login").body());
            assertThat(post(client, "/schedule", "_csrf=" + token).statusCode()).isEqualTo(403);
            assertThat(repository.count()).isZero();
        }
    }

    @Test
    void ownerChoosesSchoolAndHasReadOnlyAccess() throws Exception {
        try (var client = loggedIn("owner_test")) {
            var selection = get(client, "/schedule");
            assertThat(selection.statusCode()).isEqualTo(200);
            assertThat(selection.body()).contains("Школа тестов", "Другая школа");
            var page = get(client, "/schedule?schoolId=2&date=" + DATE);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("9В класс", "name=\"schoolId\" value=\"2\"").doesNotContain("7А класс", "+ Добавить");
            assertThat(get(client, "/schedule/new?schoolId=2").statusCode()).isEqualTo(403);
            assertThat(get(client, "/schedule?schoolId=999").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void teacherFilterUsesAccountIdentityRatherThanRequestedTeacherOrSubject() throws Exception {
        service.save(1, null, form(1, null, 1, 1, "08:00", "08:45"));
        service.save(1, null, form(2, null, 2, 2, "08:00", "08:45"));
        service.save(1, null, form(2, null, 1, 2, "08:50", "09:35"));
        try (var client = loggedIn("teacher_test")) {
            var all = get(client, "/schedule");
            assertThat(all.statusCode()).isEqualTo(200);
            assertThat(all.body()).contains("Только мои уроки", "Асанов Бакыт");
            var mine = get(client, "/schedule?onlyMine=true&teacherId=2");
            assertThat(mine.statusCode()).isEqualTo(200);
            assertThat(mine.body()).contains("Иванова Анна Сергеевна").doesNotContain("Асанов Бакыт");
            assertThat(mine.body().split("class=\"lesson-card\"", -1)).hasSize(3);
            assertThat(get(client, "/schedule/new").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void studentCanSeeOnlyAssignedClassEvenWhenAnotherClassIsRequested() throws Exception {
        service.save(1, null, standard());
        service.save(1, null, form(2, null, 2, 2, "09:00", "09:45"));
        try (var client = loggedIn("student_test")) {
            var page = get(client, "/schedule");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("7А класс").doesNotContain("7Б", "Все классы", "Только мои уроки");
            assertThat(get(client, "/schedule?classId=2").statusCode()).isEqualTo(403);
            assertThat(get(client, "/schedule?classId=20").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void unconfiguredProfilesFailClosed() throws Exception {
        jdbc.update("update users set student_class_id=null where login='student_test'");
        jdbc.update("update users set teacher_id=null where login='teacher_test'");
        try (var client = loggedIn("student_test")) {
            var page = get(client, "/schedule");
            assertThat(page.statusCode()).isEqualTo(403);
            assertThat(page.body()).contains("Класс ученика не назначен");
        }
        try (var client = loggedIn("teacher_test")) {
            assertThat(get(client, "/schedule?onlyMine=true").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void directorCannotAssignTeacherToUnapprovedSubjectIncludingForgedPost() throws Exception {
        LessonForm invalid = standard();
        invalid.setSubjectId(2L);
        assertThatThrownBy(() -> service.save(1, null, invalid)).hasMessageContaining("не назначен на выбранный предмет");
        try (var client = loggedIn("director_test")) {
            var formPage = get(client, "/schedule/new?date=" + DATE);
            assertThat(formPage.body()).contains("data-teachers=");
            var saved = post(client, "/schedule", "_csrf=" + csrf(formPage.body()) + "&requestToken=" + UUID.randomUUID()
                    + "&date=" + DATE + "&classId=1&subjectId=2&teacherId=1&roomId=1&lessonNumber=1");
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).contains("не назначен на выбранный предмет");
            assertThat(repository.count()).isZero();
        }
        assertThatThrownBy(() -> jdbc.update("""
                insert into schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token)
                values(1,1,2,1,1,?, '08:00','08:45',?)
                """, DATE, UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void directorCannotReadOrModifyAnotherSchoolAndCsrfIsRequired() throws Exception {
        LessonForm foreign = form(20, null, 20, 20, "09:00", "09:45");
        foreign.setSubjectId(20L);
        Lesson lesson = service.save(2, null, foreign);
        try (var client = loggedIn("director_test")) {
            assertThat(get(client, "/schedule?schoolId=2").statusCode()).isEqualTo(403);
            assertThat(get(client, "/schedule?classId=20").statusCode()).isEqualTo(400);
            assertThat(get(client, "/schedule/" + lesson.getId() + "/edit").statusCode()).isEqualTo(400);
            assertThat(post(client, "/schedule", "classId=1").statusCode()).isEqualTo(403);
            String token = csrf(get(client, "/schedule/new").body());
            assertThat(post(client, "/schedule", "_csrf=" + token + "&schoolId=2").statusCode()).isEqualTo(403);
            assertThat(post(client, "/schedule/" + lesson.getId(), "_csrf=" + token).statusCode()).isEqualTo(400);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(service.get(2, lesson.getId()).getSchoolId()).isEqualTo(2);
        }
        try (var client = loggedIn("other_director")) {
            var page = get(client, "/schedule?date=" + DATE);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Другая школа", "9В класс").doesNotContain("7А класс");
        }
    }

    @Test
    void rendersListAndFormsAndHandlesHttpSubmission() throws Exception {
        try (HttpClient client = loggedIn("director_test")) {
            HttpResponse<String> empty = get(client, "/schedule?date=" + DATE);
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(empty.body()).contains("Уроков пока нет");
            HttpResponse<String> page = get(client, "/schedule/new?date=" + DATE);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Новый урок", "Математика", "7А");
            var csrfMatch = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
            assertThat(csrfMatch.find()).isTrue();
            String csrf = csrfMatch.group(1);
            String body = "_csrf=" + csrf + "&requestToken=" + UUID.randomUUID()
                    + "&date=" + DATE + "&classId=1&subjectId=1&teacherId=1&roomId=1&lessonNumber=1";
            HttpResponse<String> saved = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/schedule"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(saved.statusCode()).isEqualTo(302);
            Lesson lesson = repository.findAll().getFirst();
            assertThat(get(client, "/schedule?date=" + DATE).body()).contains("08:00", "Математика", "Изменить");
            var edit = get(client, "/schedule/" + lesson.getId() + "/edit");
            assertThat(edit.statusCode()).isEqualTo(200);
            assertThat(edit.body()).contains("Редактирование урока");
            HttpResponse<String> conflict = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/schedule"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body.replaceFirst("requestToken=[^&]+",
                                    "requestToken=" + UUID.randomUUID()))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(conflict.statusCode()).isEqualTo(200);
            assertThat(conflict.body()).contains("Урок не сохранён", "Учитель уже занят", "Кабинет уже занят");
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    private LessonForm weekly() {
        LessonForm form = standard();
        form.setLessonNumber(1);
        form.setRepeatWeekly(true);
        form.setRepeatUntil(DATE.plusWeeks(2));
        return form;
    }

    @Test
    void bellScheduleHasSixLessonsAndCorrectBreaks() {
        assertThat(BellSchedule.SLOTS).extracting(s -> s.start() + "-" + s.end()).containsExactly(
                "08:00-08:45", "08:50-09:35", "09:40-10:25", "10:40-11:25", "11:30-12:15", "12:20-13:05");
        assertThat(BellSchedule.SLOTS).extracting(BellSchedule.Slot::breakMinutes).containsExactly(5, 5, 15, 5, 5, 0);
    }

    @Test
    void weeklyGridGroupsClassesWithFiveDaysAndSixRows() {
        service.savePlan(1, null, weekly());
        WeekSchedule grid = WeekSchedule.build(DATE, catalog.forSchool(1), service.forWeek(1, DATE), null);
        assertThat(grid.days()).hasSize(5);
        assertThat(grid.days().getFirst().date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(grid.days().getLast().date().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(grid.classes()).hasSize(2);
        assertThat(grid.classes()).allSatisfy(c -> {
            assertThat(c.rows()).hasSize(6);
            assertThat(c.rows()).allSatisfy(row -> assertThat(row.cells()).hasSize(5));
        });
        assertThat(grid.classes().getFirst().rows().getFirst().cells().getFirst().lessons()).hasSize(1);
        assertThat(grid.classes().getLast().lessonCount()).isZero();
        assertThat(WeekSchedule.build(DATE, catalog.forSchool(1), service.forWeek(1, DATE), 2L).classes())
                .extracting(WeekSchedule.ClassWeek::id).containsExactly(2L);
    }

    @Test
    void weeklyCreateUsesBellTimeAndIncludesEndDateWithoutDuplicates() {
        LessonForm form = weekly();
        var result = service.savePlan(1, null, form);
        assertThat(result.count()).isEqualTo(3);
        assertThat(repository.findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(1, result.first().getSeriesId()))
                .extracting(Lesson::getDate).containsExactly(DATE, DATE.plusWeeks(1), DATE.plusWeeks(2));
        assertThat(repository.findAll()).allSatisfy(l -> {
            assertThat(l.getStartTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(l.getEndTime()).isEqualTo(LocalTime.of(8, 45));
        });
        assertThat(service.savePlan(1, null, form).count()).isEqualTo(3);
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void semesterViewShowsOneEntryForEachSeriesAndEditDefaultsToWholeTerm() throws Exception {
        Lesson first = service.savePlan(1, null, weekly()).first();
        try (var client = loggedIn("director_test")) {
            var page = get(client, "/schedule?classId=1");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Расписание на полугодие", "01.09.2030", "31.12.2030")
                    .doesNotContain("Следующая неделя", "Предыдущая неделя", "Неделя с");
            assertThat(page.body().split("class=\"lesson-card\"", -1)).hasSize(2);
            var formPage = get(client, "/schedule/" + first.getId() + "/edit");
            assertThat(formPage.statusCode()).isEqualTo(200);
            assertThat(formPage.body()).containsPattern("value=\"true\"[^>]*checked=\"checked\"");
            var create = get(client, "/schedule/new?date=" + DATE);
            assertThat(create.body()).contains("2030-12-31", "Период действия расписания");
        }
    }

    @Test
    void rejectsRoomTooSmallForWholeClass() {
        jdbc.update("update schedule_class set student_count=40 where id=1");
        try {
            assertThatThrownBy(() -> service.save(1, null, standard())).hasMessageContaining("Кабинет вмещает");
            assertThat(repository.count()).isZero();
        } finally {
            jdbc.update("update schedule_class set student_count=0 where id=1");
        }
    }

    @Test
    void laterConflictRollsBackEntireWeeklyCreate() {
        LessonForm existing = standard();
        existing.setLessonNumber(1);
        existing.setDate(DATE.plusWeeks(2));
        service.savePlan(1, null, existing);
        assertThatThrownBy(() -> service.savePlan(1, null, weekly()))
                .hasMessageContaining("04.11.2030").hasMessageContaining("Учитель");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.forDate(1, DATE)).isEmpty();
    }

    @Test
    void followingEditsChangeOnlySelectedAndLaterOccurrences() {
        var created = service.savePlan(1, null, weekly());
        var series = repository.findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(1, created.first().getSeriesId());
        Lesson selected = series.get(1);
        LessonForm edit = service.editForm(1, selected.getId());
        edit.setApplyFollowing(true);
        edit.setRoomId(2L);
        edit.setLessonNumber(4);
        assertThat(service.savePlan(1, selected.getId(), edit).count()).isEqualTo(2);
        assertThat(service.get(1, series.getFirst().getId()).getRoomId()).isEqualTo(1L);
        for (Lesson later : series.subList(1, 3)) {
            Lesson saved = service.get(1, later.getId());
            assertThat(saved.getRoomId()).isEqualTo(2L);
            assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(10, 40));
        }
    }

    @Test
    void staleSeriesEditDetectsChangeToAnotherOccurrence() {
        var created = service.savePlan(1, null, weekly());
        Lesson first = created.first();
        LessonForm stale = service.editForm(1, first.getId());
        stale.setApplyFollowing(true);
        var last = repository.findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(1, first.getSeriesId()).getLast();
        LessonForm individual = service.editForm(1, last.getId());
        individual.setRoomId(2L);
        service.savePlan(1, last.getId(), individual);
        assertThatThrownBy(() -> service.savePlan(1, first.getId(), stale)).hasMessageContaining("уже изменено");
        assertThat(service.get(1, first.getId()).getRoomId()).isEqualTo(1L);
    }

    @Test
    void laterConflictRollsBackEntireFollowingEdit() {
        var created = service.savePlan(1, null, weekly());
        LessonForm blocker = form(2, null, 2, 2, "08:00", "08:45");
        blocker.setDate(DATE.plusWeeks(2));
        service.save(1, null, blocker);
        LessonForm edit = service.editForm(1, created.first().getId());
        edit.setApplyFollowing(true);
        edit.setRoomId(2L);
        assertThatThrownBy(() -> service.savePlan(1, created.first().getId(), edit))
                .hasMessageContaining("04.11.2030").hasMessageContaining("Кабинет");
        assertThat(repository.findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(1, created.first().getSeriesId()))
                .allSatisfy(l -> assertThat(l.getRoomId()).isEqualTo(1L));
    }

    @Test
    void rejectsMissingPeriodWeekendAndInvalidSlot() {
        LessonForm form = weekly();
        form.setRepeatUntil(null);
        assertThatThrownBy(() -> service.savePlan(1, null, form)).hasMessageContaining("окончание периода");
        form.setRepeatUntil(DATE.minusDays(1));
        assertThatThrownBy(() -> service.savePlan(1, null, form)).hasMessageContaining("окончание периода");
        form.setRepeatUntil(DATE.plusYears(2));
        assertThatThrownBy(() -> service.savePlan(1, null, form)).hasMessageContaining("один год");
        form.setRepeatWeekly(false);
        form.setDate(DATE.plusDays(5));
        assertThatThrownBy(() -> service.savePlan(1, null, form)).hasMessageContaining("будний");
        form.setDate(DATE);
        form.setLessonNumber(7);
        assertThatThrownBy(() -> service.savePlan(1, null, form)).hasMessageContaining("от 1 до 6");
        assertThat(repository.count()).isZero();
    }

    @Test
    void legacyLessonsRemainVisibleOutsideGrid() {
        service.save(1, null, standard());
        WeekSchedule grid = WeekSchedule.build(DATE, catalog.forSchool(1), service.forWeek(1, DATE), null);
        assertThat(grid.outsideGrid()).hasSize(1);
    }

    @Test
    void httpSemesterHasNoWeekNavigationAndPrefillsClassAndSlot() throws Exception {
        try (HttpClient client = loggedIn("director_test")) {
            var page = get(client, "/schedule?date=" + DATE.plusDays(2) + "&classId=1");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Понедельник", "Пятница", "01.09.2030", "31.12.2030",
                    "7А класс", "08:00–08:45", "10:40–11:25", "12:20–13:05", "Перемена 15 мин")
                    .doesNotContain("7Б класс", "Предыдущая неделя", "Следующая неделя", "Неделя с");
            assertThat(page.body().split("class=\"lesson-cell\"", -1)).hasSize(31);
            var form = get(client, "/schedule/new?date=" + DATE + "&classId=2&lessonNumber=4");
            assertThat(form.statusCode()).isEqualTo(200);
            assertThat(form.body()).contains("Период действия расписания", "4 урок · 10:40–11:25");
            assertThat(form.body()).containsPattern("value=\"2\"\\s+selected=\"selected\">7Б");
            assertThat(form.body()).containsPattern("value=\"4\"\\s+selected=\"selected\">4 урок");
            assertThat(get(client, "/schedule?classId=999").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void httpCanEditFollowingOccurrencesFromRecurringForm() throws Exception {
        Lesson first = service.savePlan(1, null, weekly()).first();
        try (HttpClient client = loggedIn("director_test")) {
            var page = get(client, "/schedule/" + first.getId() + "/edit");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Применить ко всем оставшимся занятиям полугодия");
            var csrfMatch = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
            assertThat(csrfMatch.find()).isTrue();
            LessonForm current = service.editForm(1, first.getId());
            String body = "_csrf=" + csrfMatch.group(1) + "&version=" + current.getVersion()
                    + "&requestToken=" + current.getRequestToken() + "&seriesRevision=" + current.getSeriesRevision()
                    + "&date=" + DATE + "&classId=1&subjectId=2&teacherId=2&roomId=2&lessonNumber=6&applyFollowing=true"
                    + "&startTime=01:00&endTime=02:00";
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/schedule/" + first.getId()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(repository.findAll()).hasSize(3).allSatisfy(l -> {
                assertThat(l.getSubjectId()).isEqualTo(2L);
                assertThat(l.getRoomId()).isEqualTo(2L);
                assertThat(l.getStartTime()).isEqualTo(LocalTime.of(12, 20));
                assertThat(l.getEndTime()).isEqualTo(LocalTime.of(13, 5));
            });
        }
    }
}
