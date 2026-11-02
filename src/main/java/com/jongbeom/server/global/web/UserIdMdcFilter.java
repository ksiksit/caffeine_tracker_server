package com.jongbeom.server.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증된 요청의 userId 를 MDC 에 넣는다. SecurityConfig 가 시큐리티 체인 안에 직접 등록한다 — 빈으로 만들면 서블릿 필터로 한 번 더 등록된다.
 * 체인이 끝나면 SecurityContext 가 비워지므로 값을 지우는 일은 바깥의 {@link RequestLoggingFilter}가 마지막 로그를 남긴 뒤에 한다.
 */
public class UserIdMdcFilter extends OncePerRequestFilter {

    static final String USER_ID_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            MDC.put(USER_ID_KEY, String.valueOf(CurrentUser.id(token.getToken())));
        }
        chain.doFilter(request, response);
    }
}
