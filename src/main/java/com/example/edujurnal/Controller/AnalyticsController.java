package com.example.edujurnal.controller;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.service.PerformanceAnalyticsService;
import com.example.edujurnal.service.PerformanceAnalyticsService.*;
import com.example.edujurnal.service.ScheduleAccessService;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AnalyticsController {
    private final PerformanceAnalyticsService analytics;
    private final ScheduleAccessService access;

    public AnalyticsController(PerformanceAnalyticsService analytics, ScheduleAccessService access) {
        this.analytics = analytics;
        this.access = access;
    }

    @GetMapping("/analytics")
    public String view(Authentication auth, Model model,
                       @RequestParam(required=false) Long studentId,
                       @RequestParam(required=false) Long yearId,
                       @RequestParam(required=false) Long quarterId,
                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required=false) Long subject,
                       @RequestParam(defaultValue="WEEK") Interval interval) {
        var user = access.currentUser(auth);
        if (!Set.of(Role.STUDENT, Role.PARENT, Role.TEACHER, Role.DIRECTOR).contains(user.getRole()))
            throw new AccessDeniedException("Аналитика недоступна для этой роли.");
        var filter = new Filter(studentId, yearId, quarterId, from, to, subject, interval);
        var page = analytics.build(auth, filter);
        var report = page.report();
        model.addAttribute("user", user);
        model.addAttribute("filter", filter);
        model.addAttribute("page", page);
        model.addAttribute("report", report);
        model.addAttribute("gradesHasData", report.average().count() > 0);
        model.addAttribute("attendanceHasData", report.attendance().stream().anyMatch(a ->
                a.present() + a.absent() + a.late() + a.unmarked() > 0));
        model.addAttribute("homeworkHasData", report.homework().acceptedPercent().count() > 0);
        model.addAttribute("attendanceMax", Math.max(1, report.attendance().stream().mapToLong(a ->
                Math.max(Math.max(a.present(), a.absent()), Math.max(a.late(), a.unmarked()))).max().orElse(1)));
        model.addAttribute("homeworkMax", Math.max(1, report.homework().statuses().values().stream().mapToLong(Long::longValue).max().orElse(1)));
        return "analytics/view";
    }
}
