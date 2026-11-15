package com.example.edujurnal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DirectorController {

    @GetMapping("/director/dashboard")
    public String dashboard() {
        return "dashboard/director";
    }
}