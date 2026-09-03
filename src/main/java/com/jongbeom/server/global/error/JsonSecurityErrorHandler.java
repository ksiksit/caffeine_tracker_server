package com.jongbeom.server.global.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jongbeom.server.global.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 시큐리티 필터 단계의 401/403 → {@link ApiResponse} 실패 봉투(UNAUTHORIZED / FORBIDDEN).
 * 컨트롤러 이전에 끝나는 요청이라 {@link GlobalExceptionHandler}를 거치지 않으므로 여기서 같은 형태를 직접 쓴다.
 *
 * <p>401 은 두 경로로 발생한다 — 토큰 없음(익명 → ExceptionTranslationFilter)과 토큰 무효(BearerTokenAuthenticationFilter).
 * 둘 다 이 핸들러로 오도록 SecurityConfig 에서 exceptionHandling 과 oauth2ResourceServer 양쪽에 등록한다.
 * 403 은 현재 역할 규칙이 없어 사실상 발생하지 않지만 형태 통일을 위해 함께 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        log.debug("인증 실패 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); // RFC 6750 — Bearer 토큰 요구 힌트
        write(response, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        log.debug("접근 거부 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code));
    }
}
