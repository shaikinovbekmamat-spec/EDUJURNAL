package com.example.edujurnal.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DirectorController {

    @GetMapping("/director/dashboard")
    public String dashboard() {
        return "dashboard/admin"; // Возвращает файл templates/dashboard/director.html
    }
}