package com.example.edujurnal.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CurrentUserAdvice {
    @ModelAttribute("currentLogin")
    public String currentLogin(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }
}
