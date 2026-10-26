package com.example.edujurnal.security;


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

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String targetUrl = "/student/dashboard";

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority();
            if ("ROLE_OWNER".equals(role)) { targetUrl = "/owner/dashboard"; break; }
            if ("ROLE_DIRECTOR".equals(role)) { targetUrl = "/director/dashboard"; break; }
            if ("ROLE_TEACHER".equals(role)) { targetUrl = "/teacher/dashboard"; break; }
            if ("ROLE_PARENT".equals(role)) { targetUrl = "/parent/dashboard"; break; }
        }

        super.setDefaultTargetUrl(targetUrl);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
