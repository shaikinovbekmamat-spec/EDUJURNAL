package com.example.edujurnal.controller;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/class-teacher")
public class ClassTeacherController {
    private final AcademicService academicService;
    private final ScheduleAccessService access;
    private final JournalService journalService;

    public ClassTeacherController(AcademicService academicService,
                                 ScheduleAccessService access,
                                 JournalService journalService) {
        this.academicService = academicService;
        this.access = access;
        this.journalService = journalService;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication auth, Model model) {
        var user = access.currentUser(auth);
        model.addAttribute("user", user);

        // Получить класс, которым руководит учитель
        var managedClass = academicService.getManagedClass(auth);
        model.addAttribute("managedClass", managedClass);

        if (managedClass != null) {
            // Получить статистику класса
            model.addAttribute("studentsCount", academicService.getStudentsCount(managedClass.get("class_id")));
            model.addAttribute("averageGrade", journalService.getClassAverageGrade(managedClass.get("class_id")));
        }

        return "class-teacher/dashboard";
    }

    @GetMapping("/journal")
    public String journal(Authentication auth,
                         @RequestParam(required = false) Long quarterId,
                         Model model) {
        var user = access.currentUser(auth);
        model.addAttribute("user", user);

        var managedClass = academicService.getManagedClass(auth);
        if (managedClass == null) {
            model.addAttribute("error", "Вы не назначены классным руководителем");
            return "class-teacher/dashboard";
        }

        long classId = (Long) managedClass.get("class_id");

        // Получить журнал класса с оценками и средними баллами
        var journalData = journalService.getClassJournal(classId, quarterId);
        model.addAttribute("journal", journalData);
        model.addAttribute("managedClass", managedClass);
        model.addAttribute("quarters", academicService.getQuartersForClass(classId));
        model.addAttribute("selectedQuarter", quarterId);

        return "class-teacher/journal";
    }

    @GetMapping("/parents")
    public String parents(Authentication auth, Model model) {
        var user = access.currentUser(auth);
        model.addAttribute("user", user);

        var managedClass = academicService.getManagedClass(auth);
        if (managedClass == null) {
            model.addAttribute("error", "Вы не назначены классным руководителем");
            return "class-teacher/dashboard";
        }

        long classId = (Long) managedClass.get("class_id");

        // Получить учеников класса
        model.addAttribute("students", academicService.getClassStudents(classId));

        // Получить существующие связи родителей
        model.addAttribute("links", academicService.getParentLinks(classId));

        model.addAttribute("managedClass", managedClass);

        return "class-teacher/parents";
    }

    @PostMapping("/parents/link")
    public String linkParent(Authentication auth,
                           @RequestParam String parentLogin,
                           @RequestParam long studentId,
                           @RequestParam(defaultValue="false") boolean returnToAccounts,
                           RedirectAttributes flash) {
        try {
            academicService.linkParentByClassTeacher(auth, parentLogin, studentId);
            flash.addFlashAttribute("success", "Связь с родителем подтверждена");
        } catch (Exception e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return returnToAccounts ? "redirect:/class-teacher/accounts#parent-links" : "redirect:/class-teacher/parents";
    }

    @PostMapping("/parents/revoke")
    public String revokeLink(Authentication auth,
                           @RequestParam long parentId,
                           @RequestParam long studentId,
                           @RequestParam(defaultValue="false") boolean returnToAccounts,
                           RedirectAttributes flash) {
        try {
            academicService.revokeParentLinkByClassTeacher(auth, parentId, studentId);
            flash.addFlashAttribute("success", "Связь с родителем отозвана");
        } catch (Exception e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return returnToAccounts ? "redirect:/class-teacher/accounts#parent-links" : "redirect:/class-teacher/parents";
    }

    @GetMapping("/accounts")
    public String accounts(Authentication auth, Model model) {
        var user = access.currentUser(auth);
        model.addAttribute("user", user);

        var managedClass = academicService.getManagedClass(auth);
        if (managedClass == null) {
            model.addAttribute("error", "Вы не назначены классным руководителем");
            return "class-teacher/dashboard";
        }

        long classId = (Long) managedClass.get("class_id");

        // Получить всех учеников и родителей класса
        model.addAttribute("students", academicService.getClassStudents(classId));
        model.addAttribute("parents", academicService.getClassParents(classId));
        model.addAttribute("links", academicService.getParentLinks(classId));
        model.addAttribute("managedClass", managedClass);

        return "class-teacher/accounts";
    }

    @PostMapping("/accounts/create")
    public String createAccount(Authentication auth,
                               @RequestParam String login,
                               @RequestParam String password,
                               @RequestParam String fullName,
                               @RequestParam Role role,
                               RedirectAttributes flash) {
        try {
            academicService.createAccountByClassTeacher(auth, login, password, fullName, role);
            flash.addFlashAttribute("success", "Учетная запись создана: " + login);
        } catch (Exception e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/class-teacher/accounts";
    }
}
