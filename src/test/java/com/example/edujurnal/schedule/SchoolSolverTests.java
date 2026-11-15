package com.example.edujurnal.schedule;

import com.example.edujurnal.dto.GenerationForm;
import com.example.edujurnal.repository.*;
import com.example.edujurnal.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SchoolSolverTests {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {5, 4, 3})
    void buildsFourteenFullClassesWithSharedTeachersAndRooms(int workingDays) {
        var catalog = mock(ScheduleCatalog.class);
        var repository = mock(LessonRepository.class);
        var schedule = mock(ScheduleService.class);
        var curriculum = mock(CurriculumService.class);
        var classes = new ArrayList<ScheduleCatalog.Option>();
        var teachers = new ArrayList<ScheduleCatalog.Option>();
        var rooms = new ArrayList<ScheduleCatalog.Option>();
        var subjects = new ArrayList<ScheduleCatalog.Option>();
        for (long i = 1; i <= 14; i++) classes.add(new ScheduleCatalog.Option(i, "Класс " + i, null, null, 28));
        for (long i = 1; i <= 29; i++) teachers.add(new ScheduleCatalog.Option(i, "Учитель " + i, null, null, null));
        for (long i = 1; i <= 22; i++) rooms.add(new ScheduleCatalog.Option(i, "Кабинет " + i, null, 32, null));
        for (long i = 1; i <= 5; i++) subjects.add(new ScheduleCatalog.Option(i, "Предмет " + i, null, null, null));
        when(catalog.forSchool(1)).thenReturn(new ScheduleCatalog.Catalog(classes, List.of(), subjects, teachers, rooms));
        when(schedule.today()).thenReturn(LocalDate.of(2030, 10, 20));
        var input = new GenerationForm();
        input.setStart(LocalDate.of(2030, 10, 21));
        input.setEnd(LocalDate.of(2030, 12, 31));
        input.setLoads(new ArrayList<>());
        for (long c = 1; c <= 14; c++) for (long s = 1; s <= 5; s++) {
            var row = new GenerationForm.Load();
            row.setClassId(c); row.setSubjectId(s); row.setTeacherId(((c - 1) * 5 + s - 1) % 29 + 1);
            row.setWeeklyCount(6); input.getLoads().add(row);
        }
        for (int day = workingDays + 1; day <= 5; day++) for (int slot = 1; slot <= 6; slot++)
            input.getUnavailable().add(new GenerationForm.Unavailable(1L, day, slot));
        var generator = new ScheduleGenerationService(catalog, repository, new ScheduleValidator(catalog), schedule, curriculum);
        var plan = generator.preview(1, input);
        assertThat(plan.entries()).hasSize(420);
        assertThat(plan.entries()).filteredOn(e -> e.getTeacherId() == 1L)
                .allMatch(e -> e.getDate().getDayOfWeek().getValue() <= workingDays);
        Set<String> occupiedClasses = new HashSet<>(), occupiedTeachers = new HashSet<>(), occupiedRooms = new HashSet<>();
        for (var e : plan.entries()) {
            String slot = e.getDate().getDayOfWeek() + ":" + e.getLessonNumber() + ":";
            assertThat(occupiedClasses.add(slot + e.getClassId())).isTrue();
            assertThat(occupiedTeachers.add(slot + e.getTeacherId())).isTrue();
            assertThat(occupiedRooms.add(slot + e.getRoomId())).isTrue();
        }
    }
}
