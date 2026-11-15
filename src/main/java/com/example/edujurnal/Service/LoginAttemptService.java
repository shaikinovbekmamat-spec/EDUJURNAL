package com.example.edujurnal.service;

import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;

@Service
public class LoginAttemptService {
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(1);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;
    private final Clock clock;

    public LoginAttemptService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("unknown-account-password");
    }

    // Return failures instead of throwing so that the counter is committed first.
    @Transactional
    public LoginResult authenticate(String login, String password) {
        User user = userRepository.findByLoginForUpdate(login).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            return new LoginResult(Status.BAD_CREDENTIALS, null, MAX_ATTEMPTS);
        }
        if (!user.isEnabled()) {
            return new LoginResult(Status.DISABLED, null, 0);
        }

        Instant now = clock.instant();
        if (user.getLockedUntil() != null) {
            if (now.isBefore(user.getLockedUntil())) {
                return new LoginResult(Status.LOCKED, null, 0);
            }
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() >= MAX_ATTEMPTS) {
                user.setLockedUntil(clock.instant().plus(LOCKOUT_DURATION));
                return new LoginResult(Status.LOCKED, null, 0);
            }
            return new LoginResult(Status.BAD_CREDENTIALS, null, MAX_ATTEMPTS - user.getFailedAttempts());
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(user.getLogin())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();
        return new LoginResult(Status.SUCCESS, principal, MAX_ATTEMPTS);
    }

    public enum Status { SUCCESS, BAD_CREDENTIALS, LOCKED, DISABLED }

    public record LoginResult(Status status, UserDetails principal, int remainingAttempts) {}
}
