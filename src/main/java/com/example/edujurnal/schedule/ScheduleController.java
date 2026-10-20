package com.example.edujurnal.schedule;

import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
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
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/**
 * Local demo adapter only. Production integration must derive school and director
 * permissions from the authenticated account, never from request parameters.
 */
@Controller
@Profile("demo")
public class ScheduleController {
    private static final long DEMO_SCHOOL = 1;
    private final ScheduleService service;
    private final ScheduleCatalog catalog;

    public ScheduleController(ScheduleService service, ScheduleCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @InitBinder("form")
    void formFields(WebDataBinder binder) {
        binder.setAllowedFields("version", "classId", "subgroupId", "subjectId", "teacherId", "roomId",
                "date", "requestToken", "lessonNumber", "repeatWeekly", "repeatUntil", "applyFollowing", "seriesRevision");
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/schedule";
    }

    @GetMapping("/schedule")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                       LocalDate date, @RequestParam(required = false) Long classId, Model model) {
        LocalDate selected = date == null ? service.today() : date;
        LocalDate monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var references = catalog.forSchool(DEMO_SCHOOL);
        if (classId != null && references.classes().stream().noneMatch(c -> c.id() == classId)) {
            throw new ScheduleException("Класс не найден в этой школе.");
        }
        LocalDate newDate = monday.isAfter(service.today()) ? monday : service.today().plusDays(1);
        while (newDate.getDayOfWeek().getValue() > 5) newDate = newDate.plusDays(1);
        model.addAttribute("date", monday);
        model.addAttribute("newDate", newDate);
        model.addAttribute("classId", classId);
        model.addAttribute("week", WeekSchedule.build(monday, references, service.forWeek(DEMO_SCHOOL, monday), classId));
        model.addAttribute("catalog", references);
        model.addAttribute("scheduleService", service);
        return "schedule/list";
    }

    @GetMapping("/schedule/new")
    public String create(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate date, @RequestParam(required = false) Long classId,
                         @RequestParam(defaultValue = "1") Integer lessonNumber, Model model, HttpSession session) {
        LessonForm form = new LessonForm();
        form.setDate(date == null ? service.today().plusDays(1) : date);
        while (form.getDate().getDayOfWeek().getValue() > 5) form.setDate(form.getDate().plusDays(1));
        form.setClassId(classId);
        form.setLessonNumber(BellSchedule.get(lessonNumber).number());
        form.setRepeatWeekly(true);
        form.setRepeatUntil(form.getDate().plusMonths(3));
        form.setRequestToken(UUID.randomUUID().toString());
        model.addAttribute("form", form);
        return prepare(null, model, session);
    }

    @GetMapping("/schedule/{id}/edit")
    public String edit(@PathVariable long id, Model model, HttpSession session) {
        Lesson lesson = service.get(DEMO_SCHOOL, id);
        if (!service.editable(lesson)) {
            throw new ScheduleException("Начавшийся или проведённый урок нельзя изменять.");
        }
        model.addAttribute("form", service.editForm(DEMO_SCHOOL, id));
        return prepare(id, model, session);
    }

    @PostMapping("/schedule")
    public String createSubmit(@ModelAttribute("form") LessonForm form, BindingResult binding,
                               @RequestParam String csrf, HttpSession session, Model model,
                               RedirectAttributes redirect) {
        return save(null, form, binding, csrf, session, model, redirect);
    }

    @PostMapping("/schedule/{id}")
    public String editSubmit(@PathVariable long id, @ModelAttribute("form") LessonForm form,
                             BindingResult binding, @RequestParam String csrf, HttpSession session,
                             Model model, RedirectAttributes redirect) {
        return save(id, form, binding, csrf, session, model, redirect);
    }

    private String save(Long id, LessonForm form, BindingResult binding, String csrf, HttpSession session,
                        Model model, RedirectAttributes redirect) {
        if (!csrf.equals(session.getAttribute("scheduleCsrf"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Откройте форму заново.");
        }
        if (!binding.hasErrors()) {
            try {
                var result = service.savePlan(DEMO_SCHOOL, id, form);
                redirect.addFlashAttribute("success", result.count() > 1
                        ? "Еженедельное расписание сохранено. Уроков: " + result.count() + "."
                        : id == null ? "Урок добавлен." : "Изменения сохранены.");
                return "redirect:/schedule?date=" + result.first().getDate() + "&classId=" + result.first().getClassId();
            } catch (ScheduleException ex) {
                ex.getErrors().forEach(error -> binding.reject("schedule", error));
            }
        }
        return prepare(id, model, session);
    }

    private String prepare(Long id, Model model, HttpSession session) {
        if (session.getAttribute("scheduleCsrf") == null) {
            session.setAttribute("scheduleCsrf", UUID.randomUUID().toString());
        }
        model.addAttribute("csrf", session.getAttribute("scheduleCsrf"));
        model.addAttribute("lessonId", id);
        model.addAttribute("catalog", catalog.forSchool(DEMO_SCHOOL));
        model.addAttribute("slots", BellSchedule.SLOTS);
        if (id != null) {
            var lesson = service.get(DEMO_SCHOOL, id);
            model.addAttribute("recurring", lesson.getSeriesId() != null);
            model.addAttribute("legacyTime", BellSchedule.number(lesson) == null
                    ? lesson.getStartTime() + "–" + lesson.getEndTime() : null);
        }
        return "schedule/form";
    }

    @ExceptionHandler(ScheduleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String error(ScheduleException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "schedule/error";
    }
}
