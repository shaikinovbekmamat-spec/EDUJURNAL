package com.example.edujurnal.security;

import com.example.edujurnal.service.LoginAttemptService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {
    private final LoginAttemptService loginAttemptService;

    public AccountAuthenticationProvider(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        var result = loginAttemptService.authenticate(authentication.getName(), password);
        // The transaction has committed before an authentication exception is raised.
        switch (result.status()) {
            case LOCKED -> throw new LockedException("Аккаунт временно заблокирован. Попробуйте снова через 5 минут.");
            case DISABLED -> throw new DisabledException("Аккаунт заблокирован администратором.");
            case BAD_CREDENTIALS -> throw new BadCredentialsException(
                    "Неверный логин или пароль. Осталось попыток: " + result.remainingAttempts());
            default -> {
                var token = UsernamePasswordAuthenticationToken.authenticated(
                        result.principal(), null, result.principal().getAuthorities());
                token.setDetails(authentication.getDetails());
                return token;
            }
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}