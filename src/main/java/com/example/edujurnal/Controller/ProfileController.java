package com.example.edujurnal.controller;

import com.example.edujurnal.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProfileController {
    private final UserService users;

    public ProfileController(UserService users) { this.users = users; }

    @GetMapping("/profile")
    public String profile() { return "profile"; }

    @PostMapping("/profile/password")
    public String changePassword(Authentication authentication,
            @RequestParam String currentPassword, @RequestParam String newPassword,
            @RequestParam String confirmation, RedirectAttributes redirect) {
        try {
            users.changePassword(authentication.getName(), currentPassword, newPassword, confirmation);
            redirect.addFlashAttribute("success", "Пароль успешно изменён.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/profile";
    }
}
