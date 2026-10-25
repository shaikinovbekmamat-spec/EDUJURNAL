package com.example.edujurnal.security;

import com.example.edujurnal.service.LoginAttemptService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;

    public CustomAuthenticationFailureHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String errorMessage;

        if (loginAttemptService.isLockedOut(request.getSession())) {
            errorMessage = "Слишком много неудачных попыток. Попробуйте позже.";
        } else {
            boolean locked = loginAttemptService.registerFailure(request.getSession());
            if (locked) {
                errorMessage = "Процедура ввода завершена. Попробуйте снова через 5 минут.";
            } else {
                int remaining = loginAttemptService.getRemainingAttempts(request.getSession());
                errorMessage = "Неверный логин или пароль. Осталось попыток: " + remaining;
            }
        }

        request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", errorMessage);
        super.setDefaultFailureUrl("/login?error=true");
        super.onAuthenticationFailure(request, response, exception);
    }
}