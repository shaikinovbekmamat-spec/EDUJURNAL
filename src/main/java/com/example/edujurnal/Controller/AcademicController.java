package com.example.edujurnal.controller;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.service.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AcademicController {
    private final AcademicService service;
    private final ScheduleAccessService access;
    private final ScheduleCatalog catalog;
    public AcademicController(AcademicService service,ScheduleAccessService access,ScheduleCatalog catalog) {
        this.service=service; this.access=access; this.catalog=catalog;
    }
    @GetMapping("/management")
    public String management(Authentication auth,@RequestParam(required=false) Long schoolId,Model model) {
        var user=access.currentUser(auth);
        model.addAttribute("user",user);
        if(user.getRole()==Role.OWNER && schoolId==null) {
            model.addAttribute("schools",access.availableSchools(user));
            return "academic/schools";
        }
        long school=service.managedSchool(auth,schoolId);
        model.addAttribute("schoolId",school);
        model.addAttribute("school",access.school(user,school));
        model.addAttribute("director",user.getRole()==Role.DIRECTOR);
        model.addAttribute("catalog",catalog.forSchool(school));
        model.addAttribute("years",service.years(school));
        model.addAttribute("quarters",service.quarters(school));
        model.addAttribute("assignments",service.assignments(school));
        model.addAttribute("accounts",service.accounts(auth,school));
        model.addAttribute("accountRoles",List.of("DIRECTOR","TEACHER","STUDENT","PARENT"));
        model.addAttribute("links",service.links(auth,school));
        model.addAttribute("classTeachers",service.classTeachers(auth,school));
        model.addAttribute("eligibleClassTeachers",service.eligibleClassTeachers(auth,school));
        return "academic/management";
    }
    @PostMapping("/management/class-teachers")
    public String classTeacher(Authentication auth,@RequestParam long classId,
                               @RequestParam(required=false) Long teacherUserId,RedirectAttributes flash) {
        service.assignClassTeacher(auth,classId,teacherUserId);
        flash.addFlashAttribute("success",teacherUserId==null ? "Назначение классного руководителя снято." : "Классный руководитель назначен.");
        return "redirect:/management#class-teachers";
    }
    @PostMapping("/management/catalog")
    public String catalog(Authentication auth,@RequestParam String type,@RequestParam String name,@RequestParam(defaultValue="0") int studentCount,RedirectAttributes flash) {
        service.createCatalog(auth,type,name,studentCount); return done(flash,null);
    }
    @PostMapping("/management/years")
    public String year(Authentication auth,@RequestParam String name,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) List<LocalDate> starts,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) List<LocalDate> ends,RedirectAttributes flash) {
        service.createYear(auth,name,start,end,starts,ends); return done(flash,null);
    }
    @PostMapping("/management/classes/{id}/edit")
    public String editClass(Authentication auth,@PathVariable long id,@RequestParam String name,
                            @RequestParam int studentCount,RedirectAttributes flash) {
        service.updateClass(auth,id,name,studentCount); return done(flash,null);
    }
    @PostMapping("/management/classes/{id}/delete")
    public String deleteClass(Authentication auth,@PathVariable long id,RedirectAttributes flash) {
        service.deleteClass(auth,id); return done(flash,null);
    }
    @PostMapping("/management/assignments/edit")
    public String editAssignment(Authentication auth,@RequestParam long oldClassId,@RequestParam long oldSubjectId,
                                 @RequestParam long oldTeacherId,@RequestParam long classId,@RequestParam long subjectId,
                                 @RequestParam long teacherId,RedirectAttributes flash) {
        service.updateAssignment(auth,oldClassId,oldSubjectId,oldTeacherId,classId,subjectId,teacherId);
        done(flash,null); return "redirect:/management#assignments";
    }
    @PostMapping("/management/assignments/delete")
    public String deleteAssignment(Authentication auth,@RequestParam long classId,@RequestParam long subjectId,
                                   @RequestParam long teacherId,RedirectAttributes flash) {
        service.deleteAssignment(auth,classId,subjectId,teacherId);
        done(flash,null); return "redirect:/management#assignments";
    }
    @PostMapping("/management/subjects/{id}/edit")
    public String editSubject(Authentication auth,@PathVariable long id,@RequestParam String name,RedirectAttributes flash) {
        service.updateSubject(auth,id,name); return done(flash,null);
    }
    @PostMapping("/management/subjects/{id}/delete")
    public String deleteSubject(Authentication auth,@PathVariable long id,RedirectAttributes flash) {
        service.deleteSubject(auth,id); return done(flash,null);
    }
    @PostMapping("/management/years/{id}/edit")
    public String editYear(Authentication auth,@PathVariable long id,@RequestParam String name,
                          @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
                          @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
                          @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) List<LocalDate> starts,
                          @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) List<LocalDate> ends,RedirectAttributes flash) {
        service.updateYear(auth,id,name,start,end,starts,ends); return done(flash,null);
    }
    @PostMapping("/management/years/{id}/delete")
    public String deleteYear(Authentication auth,@PathVariable long id,RedirectAttributes flash) {
        service.deleteYear(auth,id); return done(flash,null);
    }
    @PostMapping("/management/assignments")
    public String assign(Authentication auth,@RequestParam long classId,@RequestParam long subjectId,@RequestParam long teacherId,RedirectAttributes flash) {
        service.assign(auth,classId,subjectId,teacherId); return done(flash,null);
    }
    @PostMapping("/management/accounts")
    public String account(Authentication auth,@RequestParam(required=false) Long schoolId,@RequestParam String login,@RequestParam String password,@RequestParam String fullName,@RequestParam Role role,@RequestParam(required=false) Long classId,@RequestParam(required=false) Long teacherId,RedirectAttributes flash) {
        service.createAccount(auth,schoolId,login,password,fullName,role,classId,teacherId); return done(flash,schoolId);
    }
    @PostMapping("/management/accounts/{id}/edit")
    public String editAccount(Authentication auth,@PathVariable long id,@RequestParam(required=false) Long schoolId,
                              @RequestParam String login,@RequestParam String fullName,@RequestParam(defaultValue="") String password,
                              @RequestParam(required=false) Long classId,RedirectAttributes flash) {
        service.updateAccount(auth,schoolId,id,login,fullName,password,classId);
        done(flash,schoolId); return "redirect:/management#accounts";
    }
    @PostMapping("/management/accounts/{id}/delete")
    public String deleteAccount(Authentication auth,@PathVariable long id,@RequestParam(required=false) Long schoolId,RedirectAttributes flash) {
        service.deleteAccount(auth,schoolId,id);
        done(flash,schoolId); return "redirect:/management#accounts";
    }
    @PostMapping("/management/parents")
    public String parent(Authentication auth,@RequestParam String parentLogin,@RequestParam long studentId,RedirectAttributes flash) {
        service.linkParent(auth,null,parentLogin,studentId); return done(flash,null);
    }
    @PostMapping("/management/profiles")
    public String profile(Authentication auth,@RequestParam long userId,@RequestParam(required=false) Long classId,@RequestParam(required=false) Long teacherId,RedirectAttributes flash) {
        service.configureProfile(auth,userId,classId,teacherId);
        return done(flash,null);
    }
    @PostMapping("/management/parents/revoke")
    public String revoke(Authentication auth,@RequestParam long parentId,@RequestParam long studentId,RedirectAttributes flash) {
        service.unlinkParent(auth,parentId,studentId); return done(flash,null);
    }
    private String done(RedirectAttributes flash,Long school) {
        flash.addFlashAttribute("success","Изменения сохранены.");
        if(school!=null) flash.addAttribute("schoolId",school);
        return "redirect:/management";
    }
}
