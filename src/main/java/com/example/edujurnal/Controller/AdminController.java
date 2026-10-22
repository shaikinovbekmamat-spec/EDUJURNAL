package com.example.edujurnal.Controller;  // ← вместо com.example.edujurnal.admin
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminController {
    @GetMapping("/admin/dashboard")
    public String dashboard() {
        return "dashboard/admin";
    }
}