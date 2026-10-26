package com.example.edujurnal.controller;

import com.example.edujurnal.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class JournalController {
    private final JournalService journal;
    private final ScheduleAccessService access;
    private final FinalGradeService finals;
    private final LessonJournalService lessons;
    public JournalController(JournalService journal,ScheduleAccessService access,FinalGradeService finals,LessonJournalService lessons) { this.journal=journal;this.access=access;this.finals=finals;this.lessons=lessons; }
    @GetMapping("/journal")
    public String journal(Authentication auth,@RequestParam(defaultValue="false") boolean family,
                          @RequestParam(required=false) Long childId,@RequestParam(required=false) Long yearId,
                          @RequestParam(required=false) Long quarterId,Model model) {
        model.addAttribute("user",access.currentUser(auth));
        var view=journal.view(auth,family,childId,yearId,quarterId);
        model.addAttribute("view",view);
        model.addAttribute("finals",finals.list(auth,family,childId,yearId,quarterId));
        model.addAttribute("subjects",view.entries().stream().collect(java.util.stream.Collectors.groupingBy(
                JournalService.Entry::subject,java.util.TreeMap::new,java.util.stream.Collectors.toList())));
        return "academic/journal";
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
