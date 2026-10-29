package com.example.edujurnal.controller;

import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.dto.LessonForm;
import com.example.edujurnal.dto.SemesterSchedule;
import com.example.edujurnal.util.AcademicTerm;
import com.example.edujurnal.util.BellSchedule;

import com.example.edujurnal.entity.Lesson;
import com.example.edujurnal.service.ScheduleService;

import com.example.edujurnal.entity.School;
import com.example.edujurnal.entity.Role;
import com.example.edujurnal.service.ScheduleAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.UUID;

@Controller
public class ScheduleController {
    private final ScheduleService service;
    private final ScheduleCatalog catalog;
    private final ScheduleAccessService access;
    private final com.example.edujurnal.service.JournalService journal;

    public ScheduleController(ScheduleService service, ScheduleCatalog catalog, ScheduleAccessService access, com.example.edujurnal.service.JournalService journal) {
        this.service = service;
        this.catalog = catalog;
        this.access = access;
        this.journal = journal;
    }

    @ModelAttribute
    public void schoolContext(Authentication authentication,
                              @RequestParam(required = false) Long schoolId,
                              @RequestParam(defaultValue = "false") boolean family,
                              @RequestParam(required = false) Long childId, Model model) {
        var user = access.currentUser(authentication);
        family = user.getRole() == Role.PARENT || (user.getRole() == Role.TEACHER && family);
        model.addAttribute("family", family);
        if (family) {
            var children = journal.children(user);
            var child = access.familyChild(user, childId, children.stream().map(c -> c.id()).toList());
            model.addAttribute("children", children);
            model.addAttribute("childId", child == null ? null : child.getId());
            model.addAttribute("familyChild", child);
            var school = child == null ? null : child.getSchool();
            if (schoolId != null && (school == null || !schoolId.equals(school.getId())))
                throw new org.springframework.security.access.AccessDeniedException("Нет доступа к этой школе.");
            model.addAttribute("school", school);
            model.addAttribute("canEdit", false);
            model.addAttribute("owner", false);
            model.addAttribute("teacher", false);
            model.addAttribute("student", true);
            return;
        }
        var school = access.school(user, schoolId);
        model.addAttribute("school", school);
        model.addAttribute("canEdit", access.canEdit(user));
        model.addAttribute("owner", user.getRole() == Role.OWNER);
        model.addAttribute("teacher", user.getRole() == Role.TEACHER);
        model.addAttribute("student", user.getRole() == Role.STUDENT);
        if (user.getRole() == Role.OWNER) model.addAttribute("schools", access.availableSchools(user));
    }

    private long schoolId(Model model) {
        School school = (School) model.getAttribute("school");
        if (school == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите школу.");
        return school.getId();
    }

    @InitBinder("form")
    void formFields(WebDataBinder binder) {
        binder.setAllowedFields("version", "classId", "subgroupId", "subjectId", "teacherId", "roomId",
                "date", "requestToken", "lessonNumber", "repeatWeekly", "repeatUntil", "applyFollowing", "seriesRevision");
    }

    @GetMapping("/schedule")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                       LocalDate date, @RequestParam(required = false) Long classId,
                       @RequestParam(defaultValue = "false") boolean onlyMine,
                       Authentication authentication, Model model) {
        boolean family = Boolean.TRUE.equals(model.getAttribute("family"));
        if (family && model.getAttribute("familyChild") == null) return "schedule/family-empty";
        if (model.getAttribute("school") == null) return "schedule/select-school";
        var user = access.currentUser(authentication);
        if (!family) access.school(user, schoolId(model));
        var subject = family ? (com.example.edujurnal.entity.User) model.getAttribute("familyChild") : user;
        Long visibleClass = access.visibleClass(subject, classId);
        if (family && onlyMine) throw new org.springframework.security.access.AccessDeniedException("Фильтр преподавателя недоступен в разделе детей.");
        Long visibleTeacher = family ? null : access.visibleTeacher(user, onlyMine);
        LocalDate selected = date == null ? service.today() : date;
        AcademicTerm term = AcademicTerm.containing(selected);
        var references = catalog.forSchool(schoolId(model));
        if (visibleClass != null && references.classes().stream().noneMatch(c -> c.id() == visibleClass)) {
            throw new ScheduleException("Класс не найден в этой школе.");
        }
        var lessons = service.forTerm(schoolId(model), term, visibleClass, visibleTeacher);
        var visibleClasses = references.classes().stream()
                .filter(c -> visibleClass == null || c.id() == visibleClass)
                .filter(c -> visibleTeacher == null || lessons.stream().anyMatch(l -> l.getClassId() == c.id())).toList();
        references = new ScheduleCatalog.Catalog(visibleClasses, references.subgroups(), references.subjects(),
                references.teachers(), references.rooms());
        LocalDate newDate = term.start().isAfter(service.today()) ? term.start() : service.today().plusDays(1);
        while (newDate.getDayOfWeek().getValue() > 5) newDate = newDate.plusDays(1);
        model.addAttribute("term", term);
        model.addAttribute("terms", java.util.List.of(term.previous(), term, term.next()));
        model.addAttribute("newDate", newDate);
        model.addAttribute("classId", visibleClass);
        model.addAttribute("onlyMine", onlyMine);
        model.addAttribute("emptyMessage", onlyMine ? "На это полугодие ваших уроков пока нет." : "В школе пока нет классов.");
        model.addAttribute("timetable", SemesterSchedule.build(service.now(), term, references, lessons, visibleClass));
        model.addAttribute("catalog", references);
        model.addAttribute("studentsByClass", references.classes().stream().collect(java.util.stream.Collectors.toMap(
                ScheduleCatalog.Option::id, ScheduleCatalog.Option::studentCount)));
        model.addAttribute("scheduleService", service);
        return "schedule/list";
    }

    @PreAuthorize("hasRole('DIRECTOR')")
    @GetMapping("/schedule/new")
    public String create(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate date, @RequestParam(required = false) Long classId,
                         @RequestParam(defaultValue = "1") Integer lessonNumber, Model model) {
        LessonForm form = new LessonForm();
        var currentTerm = AcademicTerm.containing(service.today());
        form.setDate(date == null ? (currentTerm.start().isAfter(service.today())
                ? currentTerm.start() : service.today().plusDays(1)) : date);
        while (form.getDate().getDayOfWeek().getValue() > 5) form.setDate(form.getDate().plusDays(1));
        form.setClassId(classId);
        form.setLessonNumber(BellSchedule.get(lessonNumber).number());
        form.setRepeatWeekly(true);
        form.setRepeatUntil(AcademicTerm.containing(form.getDate()).end());
        form.setRequestToken(UUID.randomUUID().toString());
        model.addAttribute("form", form);
        return prepare(null, model);
    }

    @PreAuthorize("hasRole('DIRECTOR')")
    @GetMapping("/schedule/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        Lesson lesson = service.get(schoolId(model), id);
        if (!service.editable(lesson)) {
            throw new ScheduleException("Начавшийся или проведённый урок нельзя изменять.");
        }
        var form = service.editForm(schoolId(model), id);
        form.setApplyFollowing(lesson.getSeriesId() != null);
        model.addAttribute("form", form);
        return prepare(id, model);
    }

    @PreAuthorize("hasRole('DIRECTOR')")
    @PostMapping("/schedule")
    public String createSubmit(@ModelAttribute("form") LessonForm form, BindingResult binding,
                               Model model,
                               RedirectAttributes redirect) {
        return save(null, form, binding, model, redirect);
    }

    @PreAuthorize("hasRole('DIRECTOR')")
    @PostMapping("/schedule/{id}")
    public String editSubmit(@PathVariable long id, @ModelAttribute("form") LessonForm form,
                             BindingResult binding,
                             Model model, RedirectAttributes redirect) {
        return save(id, form, binding, model, redirect);
    }

    private String save(Long id, LessonForm form, BindingResult binding,
                        Model model, RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                if (id == null && form.isRepeatWeekly() && form.getDate() != null && form.getRepeatUntil() != null
                        && form.getRepeatUntil().isAfter(AcademicTerm.containing(form.getDate()).end())) {
                    throw new ScheduleException("Дата окончания не должна выходить за границы полугодия.");
                }
                var result = service.savePlan(schoolId(model), id, form);
                redirect.addFlashAttribute("success", result.count() > 1
                        ? "Расписание на полугодие сохранено. Занятий: " + result.count() + "."
                        : id == null ? "Урок добавлен." : "Изменения сохранены.");
                return "redirect:/schedule?date=" + AcademicTerm.containing(result.first().getDate()).start()
                        + "&classId=" + result.first().getClassId();
            } catch (ScheduleException ex) {
                ex.getErrors().forEach(error -> binding.reject("schedule", error));
            }
        }
        return prepare(id, model);
    }

    private String prepare(Long id, Model model) {
        model.addAttribute("lessonId", id);
        model.addAttribute("catalog", catalog.forSchool(schoolId(model)));
        model.addAttribute("subjectTeachers", catalog.subjectTeachers(schoolId(model)));
        model.addAttribute("slots", BellSchedule.SLOTS);
        if (id != null) {
            var lesson = service.get(schoolId(model), id);
            model.addAttribute("recurring", lesson.getSeriesId() != null);
            model.addAttribute("legacyTime", BellSchedule.number(lesson) == null
                    ? lesson.getStartTime() + "–" + lesson.getEndTime() : null);
        }
        return "schedule/form";
    }

}
