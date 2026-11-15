package com.example.edujurnal.controller;
import com.example.edujurnal.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller
public class AttendanceController {
 private final AttendanceService attendance; private final JournalBrowserService browser; private final JournalService journal;
 public AttendanceController(AttendanceService attendance,JournalService journal,JournalBrowserService browser){this.attendance=attendance;this.journal=journal;this.browser=browser;}
 @GetMapping("/attendance") public String page(Authentication auth,@RequestParam(defaultValue="false") boolean family,@RequestParam(required=false) Long childId,@RequestParam(required=false) Long yearId,@RequestParam(required=false) Long quarterId,@RequestParam(required=false) Long classId,@RequestParam(required=false) Long studentId,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,Model model){
  browser.prepare(auth,family,childId,yearId,quarterId,classId,studentId,subject,search,page,model,"/attendance");model.addAttribute("attendance",attendance);return "academic/attendance";
 }
 @PostMapping("/journal/attendance") public String save(Authentication auth,@RequestParam long lessonId,@RequestParam long studentId,@RequestParam String status,@RequestParam(defaultValue="") String reason,@RequestParam(defaultValue="") String correction,@RequestParam long version,@RequestParam(required=false) Long classId,RedirectAttributes flash){
  attendance.save(auth,lessonId,studentId,status,reason,correction,version);flash.addFlashAttribute("success","Посещаемость сохранена."); if(classId!=null){flash.addAttribute("classId",classId);flash.addAttribute("studentId",studentId);}return "redirect:/attendance";
 }
 @GetMapping(value="/notifications", params="type=attendance") public String notifications(Authentication auth,@RequestParam(required=false) Long childId,@RequestParam(defaultValue="false") boolean unread,@RequestParam(defaultValue="0") int page,@RequestParam(required=false) String type,Model model){ page=Math.max(0,Math.min(page,100000)); var list=attendance.notifications(auth,childId,unread,page,31); model.addAttribute("notifications",list.stream().limit(30).toList());model.addAttribute("more",list.size()>30);model.addAttribute("pageNumber",page);model.addAttribute("children",attendance.children(auth));model.addAttribute("childId",childId);model.addAttribute("unread",unread);model.addAttribute("attendance",attendance);return "academic/notifications";}
 @PostMapping("/notifications/{id}/read") public String read(Authentication auth,@PathVariable long id){attendance.read(auth,id);return "redirect:/notifications?type=attendance";}
 @GetMapping("/attendance/history") public String history(Authentication auth,@RequestParam long lessonId,@RequestParam long studentId,Model model){model.addAttribute("history",attendance.history(auth,lessonId,studentId));model.addAttribute("attendance",attendance);return "academic/attendance-history";}
}
