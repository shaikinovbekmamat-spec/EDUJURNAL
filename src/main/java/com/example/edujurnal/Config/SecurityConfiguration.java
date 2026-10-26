package com.example.edujurnal.config;

import com.example.edujurnal.security.AccountAuthenticationProvider;
import com.example.edujurnal.security.CustomAuthenticationFailureHandler;
import com.example.edujurnal.security.CustomAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private final AccountAuthenticationProvider authenticationProvider;
    private final CustomAuthenticationSuccessHandler successHandler;
    private final CustomAuthenticationFailureHandler failureHandler;

    public SecurityConfiguration(AccountAuthenticationProvider authenticationProvider,
                                 CustomAuthenticationSuccessHandler successHandler,
                                 CustomAuthenticationFailureHandler failureHandler) {
        this.authenticationProvider = authenticationProvider;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/schedule.css", "/schedule.js").permitAll()
                        .requestMatchers("/management", "/management/**").hasAnyRole("OWNER", "DIRECTOR")
                        .requestMatchers(HttpMethod.POST, "/journal/**").hasRole("TEACHER")
                        .requestMatchers("/journal", "/journal/**").hasAnyRole("DIRECTOR", "TEACHER", "STUDENT", "PARENT")
                        .requestMatchers(HttpMethod.POST, "/schedule", "/schedule/**").hasRole("DIRECTOR")
                        .requestMatchers("/schedule/new", "/schedule/*/edit").hasRole("DIRECTOR")
                        .requestMatchers("/owner/**").hasRole("OWNER")
                        .requestMatchers("/director/**").hasRole("DIRECTOR")
                        .requestMatchers("/teacher/**").hasRole("TEACHER")
                        .requestMatchers("/student/**").hasRole("STUDENT")
                        .requestMatchers("/parent/**").hasRole("PARENT")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/perform-login")
                        .usernameParameter("login")
                        .passwordParameter("password")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .authenticationManager(authenticationManager());

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(authenticationProvider);
    }
}
