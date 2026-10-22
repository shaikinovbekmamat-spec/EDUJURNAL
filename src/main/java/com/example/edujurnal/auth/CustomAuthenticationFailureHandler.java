package com.example.edujurnal.auth;

import com.example.edujurnal.Entity.User;
import com.example.edujurnal.Service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final UserService userService;

    public CustomAuthenticationFailureHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String phoneNumber = request.getParameter("phoneNumber");
        String errorMessage = "Неверный номер или пароль";

        if (phoneNumber != null) {
            User user = userService.findByPhoneNumber(phoneNumber);

            if (user != null) {
                if (!user.isEnabled()) {
                    errorMessage = "Аккаунт заблокирован после 3 неудачных попыток входа";
                } else {
                    userService.increaseFailedAttempts(phoneNumber);
                    user = userService.findByPhoneNumber(phoneNumber);

                    int remainingAttempts = 3 - user.getFailedAttempts();

                    if (remainingAttempts > 0) {
                        errorMessage = "Неверный пароль. Осталось попыток: " + remainingAttempts;
                    } else {
                        errorMessage = "Аккаунт заблокирован после 3 неудачных попыток входа";
                    }
                }
            }
        }

        request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", errorMessage);
        super.setDefaultFailureUrl("/login?error=true");
        super.onAuthenticationFailure(request, response, exception);
    }
}
