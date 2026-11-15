package com.example.edujurnal.auth;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.UserRepository;
import com.example.edujurnal.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
class AccountLockoutTests {
    @Autowired UserRepository repository;
    @Autowired UserService users;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;

    private User user;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        user = users.createUser("lockout-" + UUID.randomUUID(), "correct-password", Role.OWNER, "Test", null);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @AfterEach
    void cleanUp() {
        repository.deleteById(user.getId());
    }

    @Test
    void thirdFailureLocksAccountAcrossSessionsAndShowsReadableMessage() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            MockHttpSession session = new MockHttpSession();
            assertThat(login(session, "wrong").getResponse().getRedirectedUrl()).isEqualTo("/login?error=true");
            assertThat(savedUser().getFailedAttempts()).isEqualTo(attempt);
            String errorPage = mvc.perform(get("/login?error=true").session(session))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(errorPage).contains(attempt == 3 ? "Аккаунт временно заблокирован" : "Осталось попыток: " + (3 - attempt));
        }
        Instant lockedUntil = savedUser().getLockedUntil();
        assertThat(lockedUntil).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(301));
        MockHttpSession anotherBrowser = new MockHttpSession();
        assertThat(login(anotherBrowser, "correct-password").getResponse().getRedirectedUrl()).isEqualTo("/login?error=true");
        assertThat(anotherBrowser.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        assertThat(savedUser().getLockedUntil()).isEqualTo(lockedUntil);
    }

    @Test
    void successfulLoginResetsConsecutiveFailures() throws Exception {
        failLogin();
        failLogin();
        assertThat(login(new MockHttpSession(), "correct-password").getResponse().getRedirectedUrl())
                .isEqualTo("/owner/dashboard");
        assertThat(savedUser().getFailedAttempts()).isZero();
        failLogin();
        assertThat(savedUser().getFailedAttempts()).isEqualTo(1);
        assertThat(savedUser().getLockedUntil()).isNull();
    }

    @Test
    void expiredLockAllowsCorrectPassword() {
        setExpiredLock();
        assertThat(authenticate("correct-password").isAuthenticated()).isTrue();
        assertThat(savedUser().getFailedAttempts()).isZero();
        assertThat(savedUser().getLockedUntil()).isNull();
    }

    @Test
    void expiredLockStartsNewAttemptCount() {
        setExpiredLock();
        failLogin();
        assertThat(savedUser().getFailedAttempts()).isEqualTo(1);
        assertThat(savedUser().getLockedUntil()).isNull();
    }

    @Test
    void simultaneousFailuresAreCountedOnceEach() throws Exception {
        try (var executor = Executors.newFixedThreadPool(3)) {
            var tasks = new ArrayList<Callable<Class<?>>>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> {
                    try {
                        authenticate("wrong");
                        return Void.class;
                    } catch (BadCredentialsException | LockedException exception) {
                        return exception.getClass();
                    }
                });
            }
            var results = executor.invokeAll(tasks, 15, TimeUnit.SECONDS);
            var exceptions = new ArrayList<Class<?>>();
            for (var result : results) exceptions.add(result.get());
            assertThat(exceptions).containsExactlyInAnyOrder(
                    BadCredentialsException.class, BadCredentialsException.class, LockedException.class);
        }
        assertThat(savedUser().getFailedAttempts()).isEqualTo(3);
        assertThatThrownBy(() -> authenticate("correct-password")).isInstanceOf(LockedException.class);
    }

    @Test
    void disabledAccountStaysDisabledAfterTemporaryLockExpires() {
        setExpiredLock();
        User disabled = savedUser();
        disabled.setEnabled(false);
        repository.saveAndFlush(disabled);
        assertThatThrownBy(() -> authenticate("correct-password")).isInstanceOf(DisabledException.class);
    }

    @Test
    void failuresForOtherAndUnknownAccountsDoNotLockThisAccount() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated("missing-account", "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThat(authenticate("correct-password").isAuthenticated()).isTrue();
        assertThat(savedUser().getFailedAttempts()).isZero();
    }

    private MvcResult login(MockHttpSession session, String password) throws Exception {
        var page = mvc.perform(get("/login").session(session)).andReturn();
        CsrfToken csrf = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        return mvc.perform(post("/perform-login").session(session)
                .param(csrf.getParameterName(), csrf.getToken())
                .param("login", user.getLogin()).param("password", password)).andReturn();
    }

    private org.springframework.security.core.Authentication authenticate(String password) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getLogin(), password));
    }

    private void failLogin() {
        assertThatThrownBy(() -> authenticate("wrong")).isInstanceOf(BadCredentialsException.class);
    }

    private User savedUser() {
        return repository.findById(user.getId()).orElseThrow();
    }

    private void setExpiredLock() {
        User locked = savedUser();
        locked.setFailedAttempts(3);
        locked.setLockedUntil(Instant.now().minusSeconds(1));
        repository.saveAndFlush(locked);
    }
}
