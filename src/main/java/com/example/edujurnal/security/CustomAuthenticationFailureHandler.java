package com.example.edujurnal.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {
    public CustomAuthenticationFailureHandler() {
        super("/login?error=true");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String message = "Не удалось выполнить вход. Попробуйте позже.";
        if (exception instanceof BadCredentialsException || exception instanceof LockedException
                || exception instanceof DisabledException) {
            message = exception.getMessage();
        }
        // The superclass would overwrite this display message with an exception object.
        request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", message);
        getRedirectStrategy().sendRedirect(request, response, "/login?error=true");
    }
}