package com.example.edujurnal.Controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout,
                        HttpSession session,
                        Model model) {
        if (error != null) {
            Object msg = session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
            model.addAttribute("error", msg != null ? msg.toString() : "Неверный логин или пароль");
            session.removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
        }
        if (logout != null) {
            model.addAttribute("message", "Вы успешно вышли из системы");
        }
        return "login";
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/schedule";
    }
}