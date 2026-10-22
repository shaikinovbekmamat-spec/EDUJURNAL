package com.example.edujurnal.Controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.AuthenticationException;
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
            Object ex = session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
            String errorMessage = (ex instanceof AuthenticationException ae && ae.getMessage() != null)
                    ? ae.getMessage()
                    : "Неверный номер или пароль";
            model.addAttribute("error", errorMessage);
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