package com.jongbeom.server.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 MDC 에 requestId 를 넣고 완료 시 한 줄을 남긴다. 시큐리티 401/403 도 남기도록 시큐리티 필터 체인보다 바깥에 둔다.
 * 쿼리스트링과 바디는 남기지 않는다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_KEY = "requestId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(REQUEST_ID_KEY, UUID.randomUUID().toString());
        long startedAt = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR; // 예외가 필터 밖으로 전파되면 컨테이너가 500 으로 응답한다
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("요청 완료 method={}, path={}, status={}, durationMs={}",
                    request.getMethod(), request.getRequestURI(), status, durationMs);
            MDC.remove(UserIdMdcFilter.USER_ID_KEY);
            MDC.remove(REQUEST_ID_KEY);
        }
    }
}
