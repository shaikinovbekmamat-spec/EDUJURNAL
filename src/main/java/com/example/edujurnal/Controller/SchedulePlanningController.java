package com.example.edujurnal.controller;

import com.example.edujurnal.dto.GenerationForm;
import com.example.edujurnal.entity.School;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.service.*;
import com.example.edujurnal.util.AcademicTerm;
import com.example.edujurnal.util.BellSchedule;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@PreAuthorize("hasRole('DIRECTOR')")

public class SchedulePlanningController {
    private final ScheduleAccessService access;
    private final ScheduleCatalog catalog;
    private final ScheduleService schedule;
    private final CurriculumService curriculum;
    private final ScheduleGenerationService generation;
    private record Pending(String token, ScheduleGenerationService.Plan plan) {}

    public SchedulePlanningController(ScheduleAccessService access, ScheduleCatalog catalog, ScheduleService schedule,
                                      CurriculumService curriculum, ScheduleGenerationService generation) {
        this.access = access; this.catalog = catalog; this.schedule = schedule;
        this.curriculum = curriculum; this.generation = generation;
    }

    @ModelAttribute
    public void context(Authentication auth, @RequestParam(required = false) Long schoolId, Model model) {
        var user = access.currentUser(auth);
        var school = access.school(user, schoolId);
        if (school == null) throw new ScheduleException("Выберите школу.");
        model.addAttribute("school", school);
        model.addAttribute("canEdit", access.canEdit(user));
        model.addAttribute("catalog", catalog.forSchool(school.getId()));
        model.addAttribute("slots", BellSchedule.SLOTS);
        model.addAttribute("dayNames", List.of("Понедельник", "Вторник", "Среда", "Четверг", "Пятница"));
    }

    private long school(Model model) { return ((School) model.getAttribute("school")).getId(); }

    @InitBinder("generationForm")
    void dateFields(WebDataBinder binder) { binder.setAllowedFields("start", "end"); }

    @InitBinder("curriculumForm")
    void loadFields(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(200);
        binder.setAllowedFields("loads[*].subjectId", "loads[*].teacherId", "loads[*].roomId", "loads[*].weeklyCount");
    }

    @GetMapping("/schedule/generate")
    public String overview(Model model) {
        if (!model.containsAttribute("generationForm")) {
            var form = new GenerationForm();
            var start = schedule.today().plusDays(1);
            if (start.isBefore(AcademicTerm.containing(start).start())) start = AcademicTerm.containing(start).start();
            while (start.getDayOfWeek().getValue() > 5) start = start.plusDays(1);
            form.setStart(start);
            form.setEnd(AcademicTerm.containing(start).end());
            model.addAttribute("generationForm", form);
        }
        long school = school(model);
        var classes = curriculum.summary(school);
        model.addAttribute("classes", classes);
        model.addAttribute("readyCount", classes.stream().filter(c -> c.hours() > 0).count());
        model.addAttribute("totalHours", classes.stream().mapToInt(CurriculumService.ClassSummary::hours).sum());
        model.addAttribute("problems", curriculum.problems(school));
        model.addAttribute("settings", curriculum.settings(school));
        model.addAttribute("workingDays", curriculum.workingDays(school));
        return "schedule/generate";
    }

    @GetMapping({"/management/curriculum", "/schedule/generate/curriculum"})
    public String editClass(@RequestParam long classId, Model model) {
        model.addAttribute("curriculumForm", curriculum.classForm(school(model), classId));
        return prepareClass(classId, model);
    }

    private String prepareClass(long classId, Model model) {
        long school = school(model);
        model.addAttribute("classId", classId);
        var refs = catalog.forSchool(school);
        Map<Long, List<ScheduleCatalog.Option>> teachers = new HashMap<>();
        var assignments = catalog.teachingAssignments(school);
        for (var subject : refs.subjects()) teachers.put(subject.id(), refs.teachers().stream().filter(t ->
                assignments.stream().anyMatch(a -> a.subjectId() == subject.id() && a.teacherId() == t.id())).toList());
        model.addAttribute("subjectTeachers", teachers);
        return "schedule/curriculum";
    }

    @PostMapping({"/management/curriculum", "/schedule/generate/curriculum"})
    public String saveClass(@RequestParam long classId, @ModelAttribute("curriculumForm") GenerationForm form,
                            BindingResult binding, Model model, RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                curriculum.saveClass(school(model), classId, form);
                redirect.addFlashAttribute("success", "Нагрузка класса и учителей сохранена.");
                return "redirect:/management#workload";
            } catch (ScheduleException ex) { ex.getErrors().forEach(e -> binding.reject("curriculum", e)); }
        }
        return prepareClass(classId, model);
    }

    @PostMapping({"/management/copy", "/schedule/generate/copy"})
    public String copy(@RequestParam long sourceClassId, @RequestParam long classId, Model model, RedirectAttributes redirect) {
        try {
            curriculum.copy(school(model), sourceClassId, classId);
            redirect.addFlashAttribute("success", "План скопирован. Проверьте преподавателей и часы для этого класса.");
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/management/curriculum?classId=" + classId;
    }

    @PostMapping("/schedule/generate/settings")
    public String settings(@RequestParam int dailyLimit, @RequestParam int daysPerWeek, Model model, RedirectAttributes redirect) {
        try {
            curriculum.saveSettings(school(model), dailyLimit, daysPerWeek);
            redirect.addFlashAttribute("success", "Ограничения школы сохранены.");
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/schedule/generate#constraints";
    }

    @PostMapping("/schedule/generate/working-days")
    public String workingDays(@RequestParam long teacherId,
                              @RequestParam(required = false) List<Integer> days, Model model, RedirectAttributes redirect) {
        try {
            curriculum.saveWorkingDays(school(model), teacherId, days);
            redirect.addFlashAttribute("success", "Рабочие дни учителя сохранены.");
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/schedule/generate#constraints";
    }

    @PostMapping("/schedule/generate/preview")
    public String preview(@ModelAttribute("generationForm") GenerationForm form, BindingResult binding,
                          Model model, HttpSession session) {
        if (!binding.hasErrors()) {
            try {
                var plan = generation.previewSchool(school(model), form.getStart(), form.getEnd());
                session.setAttribute("schoolScheduleDraft", new Pending(UUID.randomUUID().toString(), plan));
                return "redirect:/schedule/generate/draft";
            } catch (ScheduleException ex) { ex.getErrors().forEach(e -> binding.reject("generation", e)); }
        }
        return overview(model);
    }

    @GetMapping("/schedule/generate/draft")
    public String draft(@RequestParam(defaultValue = "class") String view, Model model, HttpSession session) {
        var pending = (Pending) session.getAttribute("schoolScheduleDraft");
        if (pending == null || pending.plan().schoolId() != school(model)) return "redirect:/schedule/generate";
        model.addAttribute("plan", pending.plan());
        model.addAttribute("previewToken", pending.token());
        model.addAttribute("view", view.equals("teacher") ? "teacher" : "class");
        var refs = catalog.forSchool(school(model));
        model.addAttribute("groups", view.equals("teacher") ? refs.teachers() : refs.classes());
        return "schedule/draft";
    }

    @PostMapping("/schedule/generate/pin")
    public String pin(@RequestParam String previewToken, @RequestParam String entryToken, Model model,
                      HttpSession session, RedirectAttributes redirect) {
        try {
            var pending = pending(session, previewToken, school(model));
            session.setAttribute("schoolScheduleDraft", new Pending(UUID.randomUUID().toString(), generation.pin(pending.plan(), entryToken)));
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/schedule/generate/draft";
    }

    @PostMapping("/schedule/generate/regenerate")
    public String regenerate(@RequestParam String previewToken, Model model, HttpSession session, RedirectAttributes redirect) {
        try {
            var pending = pending(session, previewToken, school(model));
            var plan = generation.regenerate(pending.plan());
            session.setAttribute("schoolScheduleDraft", new Pending(UUID.randomUUID().toString(), plan));
            redirect.addFlashAttribute("success", "Расписание пересчитано. Закреплённые уроки остались на своих местах.");
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/schedule/generate/draft";
    }

    @PostMapping("/schedule/generate/move")
    public String move(@RequestParam String previewToken, @RequestParam String entryToken, @RequestParam int weekday,
                       @RequestParam int lessonNumber, @RequestParam long roomId, Model model, HttpSession session,
                       RedirectAttributes redirect) {
        try {
            var pending = pending(session, previewToken, school(model));
            var changed = generation.move(pending.plan(), entryToken, weekday, lessonNumber, roomId);
            session.setAttribute("schoolScheduleDraft", new Pending(UUID.randomUUID().toString(), changed));
            redirect.addFlashAttribute("success", "Урок перенесён в черновике. Пересечений нет.");
        } catch (ScheduleException ex) { redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors())); }
        return "redirect:/schedule/generate/draft";
    }

    @PostMapping("/schedule/generate/save")
    public String save(@RequestParam String previewToken, @RequestParam(defaultValue = "false") boolean confirmReplacement,
                       Model model, HttpSession session, RedirectAttributes redirect) {
        try {
            var pending = pending(session, previewToken, school(model));
            if (!confirmReplacement) throw new ScheduleException("Подтвердите применение расписания на выбранный период.");
            int count = generation.save(school(model), pending.plan());
            session.removeAttribute("schoolScheduleDraft");
            redirect.addFlashAttribute("success", "Расписание всей школы применено. Занятий: " + count + ".");
            return "redirect:/schedule?date=" + pending.plan().start();
        } catch (ScheduleException ex) {
            redirect.addFlashAttribute("generationError", String.join(" ", ex.getErrors()));
            return "redirect:/schedule/generate/draft";
        }
    }

    private Pending pending(HttpSession session, String token, long school) {
        var pending = (Pending) session.getAttribute("schoolScheduleDraft");
        if (pending == null || !pending.token().equals(token) || pending.plan().schoolId() != school)
            throw new ScheduleException("Черновик устарел. Откройте его заново или составьте новый.");
        return pending;
    }
}
