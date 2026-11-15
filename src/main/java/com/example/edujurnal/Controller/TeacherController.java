package com.example.edujurnal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TeacherController {
    @GetMapping("/teacher/dashboard")
    public String dashboard() {
        return "dashboard/teacher";
    }
}
