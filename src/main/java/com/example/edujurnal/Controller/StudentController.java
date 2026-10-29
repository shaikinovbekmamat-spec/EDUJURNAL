package com.example.edujurnal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import com.example.edujurnal.service.StudentDashboardService;

@Controller
public class StudentController {
    private final StudentDashboardService dashboard;
    public StudentController(StudentDashboardService dashboard) { this.dashboard = dashboard; }
    @GetMapping("/student/dashboard")
    public String dashboard(Authentication auth, Model model) {
        model.addAttribute("dashboard", dashboard.view(auth));
        return "dashboard/student";
    }
}
