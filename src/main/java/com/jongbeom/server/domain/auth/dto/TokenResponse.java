package com.jongbeom.server.domain.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        long refreshExpiresIn
) {
    /** 파라미터 순서는 record 컴포넌트 순서와 동일하게 유지(호출부 오독 방지). */
    public static TokenResponse bearer(
            String accessToken, String refreshToken,
            long expiresIn, long refreshExpiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, refreshExpiresIn);
    }
}
