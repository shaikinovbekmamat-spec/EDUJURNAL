package com.example.edujurnal.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 3;
    private static final String SESSION_KEY = "LOGIN_ATTEMPTS";
    private static final String LOCKOUT_KEY = "LOGIN_LOCKOUT_UNTIL";
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 минут

    public boolean isLockedOut(HttpSession session) {
        Long lockoutUntil = (Long) session.getAttribute(LOCKOUT_KEY);
        if (lockoutUntil == null) return false;

        if (System.currentTimeMillis() > lockoutUntil) {
            session.removeAttribute(LOCKOUT_KEY);
            session.removeAttribute(SESSION_KEY);
            return false;
        }
        return true;
    }

    public int getRemainingAttempts(HttpSession session) {
        Integer attempts = (Integer) session.getAttribute(SESSION_KEY);
        if (attempts == null) attempts = 0;
        return MAX_ATTEMPTS - attempts;
    }

    public boolean registerFailure(HttpSession session) {
        Integer attempts = (Integer) session.getAttribute(SESSION_KEY);
        attempts = (attempts == null) ? 1 : attempts + 1;
        session.setAttribute(SESSION_KEY, attempts);

        if (attempts >= MAX_ATTEMPTS) {
            session.setAttribute(LOCKOUT_KEY, System.currentTimeMillis() + LOCKOUT_DURATION_MS);
            return true;
        }
        return false;
    }

    public void reset(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
        session.removeAttribute(LOCKOUT_KEY);
    }
}