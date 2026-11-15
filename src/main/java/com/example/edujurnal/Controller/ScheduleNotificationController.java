package com.example.edujurnal.controller;

import com.example.edujurnal.service.SchoolMessageService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ScheduleNotificationController {
    private final SchoolMessageService messages;

    public ScheduleNotificationController(SchoolMessageService messages) {
        this.messages = messages;
    }

    @GetMapping("/notifications/schedule")
    public String list(Authentication auth, @RequestParam(defaultValue="false") boolean unread,
                       @RequestParam(defaultValue="0") int page, Model model) {
        page = Math.max(0, Math.min(page, 100000));
        var rows = messages.scheduleNotifications(auth, unread, page);
        model.addAttribute("notifications", rows.stream().limit(20).toList());
        model.addAttribute("more", rows.size() > 20);
        model.addAttribute("pageNumber", page);
        model.addAttribute("unread", unread);
        return "academic/schedule-notifications";
    }

    @PostMapping("/notifications/schedule/{id}/read")
    public String read(Authentication auth, @PathVariable long id,
                       @RequestParam(defaultValue="false") boolean unread,
                       @RequestParam(defaultValue="0") int page, RedirectAttributes redirect) {
        messages.read(auth, id);
        redirect.addAttribute("unread", unread);
        redirect.addAttribute("page", Math.max(0, Math.min(page, 100000)));
        return "redirect:/notifications/schedule";
    }
}
