package com.example.edujurnal.controller;

import com.example.edujurnal.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class JournalController {
    private final JournalService journal; private final JournalBrowserService browser;
    private final ScheduleAccessService access;
    private final FinalGradeService finals;
    private final LessonJournalService lessons;
    public JournalController(JournalService journal,ScheduleAccessService access,FinalGradeService finals,LessonJournalService lessons,JournalBrowserService browser) { this.journal=journal;this.access=access;this.finals=finals;this.lessons=lessons;this.browser=browser; }
    @GetMapping("/journal")
    public String journal(Authentication auth,@RequestParam(defaultValue="false") boolean family,
                          @RequestParam(required=false) Long childId,@RequestParam(required=false) Long yearId,
                          @RequestParam(required=false) Long quarterId,@RequestParam(required=false) Long classId,@RequestParam(required=false) Long studentId,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,Model model) {
        var view=browser.prepare(auth,family,childId,yearId,quarterId,classId,studentId,subject,search,page,model,"/journal");
        model.addAttribute("finals", (view.teacher() || access.currentUser(auth).getRole()==com.example.edujurnal.entity.Role.DIRECTOR) && studentId==null ? java.util.List.of() : finals.list(auth,family,childId,yearId,quarterId,studentId,subject));        return "academic/journal";
    }
    @PostMapping("/journal/grades")
    public String grade(Authentication auth,@RequestParam long lessonId,@RequestParam long studentId,@RequestParam int value,@RequestParam(defaultValue="false") boolean returnToLesson,RedirectAttributes flash) {
        journal.saveGrade(auth,lessonId,studentId,value);
        flash.addFlashAttribute("success","Оценка сохранена. Изменить или отменить её нельзя.");
        return returnToLesson ? "redirect:/journal/lessons/"+lessonId : "redirect:/journal";
    }
    @GetMapping("/journal/today")
    public String today(Authentication auth,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,Model model) {
        if(date==null) date=journal.today();
        model.addAttribute("lessons",lessons.lessons(auth,date));
        model.addAttribute("user",access.currentUser(auth));
        model.addAttribute("date",date);
        model.addAttribute("today",journal.today());
        return "academic/lessons";
    }
    @GetMapping("/journal/lessons/{id}")
    public String lesson(Authentication auth,@PathVariable long id,Model model) {
        model.addAttribute("page",lessons.page(auth,id));
        model.addAttribute("user",access.currentUser(auth));
        return "academic/lesson";
    }
    @PostMapping("/journal/finals")
    public String finalGrade(Authentication auth,@RequestParam long studentId,@RequestParam long subjectId,
                             @RequestParam long yearId,@RequestParam(required=false) Long quarterId,@RequestParam int value,RedirectAttributes flash) {
        finals.save(auth,studentId,subjectId,yearId,quarterId,value);
        flash.addFlashAttribute("success","Итоговая оценка подтверждена. Изменение и отмена запрещены.");
        flash.addAttribute("yearId",yearId);
        if(quarterId!=null) flash.addAttribute("quarterId",quarterId);
        return "redirect:/journal";
    }
}
