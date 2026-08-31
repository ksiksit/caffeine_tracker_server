package com.jongbeom.server.global.web;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 인증 principal({@link Jwt})에서 현재 사용자를 꺼내는 컨트롤러 공용 헬퍼.
 * JWT subject 에는 {@code users.id}가 문자열로 들어 있다 (JwtTokenProvider 발급 규약).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
