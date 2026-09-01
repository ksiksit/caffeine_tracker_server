package com.jongbeom.server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Spring Security 설정 — 무상태 JWT 리소스 서버. */
@Configuration
public class SecurityConfig {

    /** 인증 없이 호출되는 공개 auth 엔드포인트 (POST). */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/auth/signup", "/api/auth/login", "/api/auth/refresh"};

    /** 배포 헬스체크 (GET). */
    private static final String[] ACTUATOR_HEALTH_ENDPOINTS = {
            "/actuator/health", "/actuator/health/**"};

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // 무상태 JWT API — 브라우저 세션/폼로그인이 없으므로 CSRF 토큰도 불필요.
                // CORS 미설정: 클라이언트가 iOS 네이티브뿐이라 브라우저 프리플라이트가 없다.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, ACTUATOR_HEALTH_ENDPOINTS).permitAll()
                        // 새 도메인 엔드포인트는 여기 등록하지 않으면 기본 authenticated (CLAUDE.md 규칙)
                        .anyRequest().authenticated())
                // 인증 실패(401)는 엔트리포인트 미설정으로 빈 바디 — ErrorResponse 아님 (수정은 동작 변경이라 보류)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
