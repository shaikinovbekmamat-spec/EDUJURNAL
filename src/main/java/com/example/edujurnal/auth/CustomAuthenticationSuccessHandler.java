package com.example.edujurnal.auth;

import com.example.edujurnal.Service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserService userService;

    public CustomAuthenticationSuccessHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String phoneNumber = authentication.getName();
        userService.resetFailedAttempts(phoneNumber);

        // Выбираем страницу в зависимости от роли
        String targetUrl = "/student/dashboard"; // по умолчанию — ученик

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority(); // "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT"
            if ("ROLE_ADMIN".equals(role)) {
                targetUrl = "/admin/dashboard";
                break;
            } else if ("ROLE_TEACHER".equals(role)) {
                targetUrl = "/teacher/dashboard";
                break;
            }
        }

        super.setDefaultTargetUrl(targetUrl);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}