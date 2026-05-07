package com.jongbeom.server.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 발급 응답")
public record TokenResponse(
        @Schema(description = "Access Token (JWT). 인증이 필요한 요청에 `Authorization: Bearer {accessToken}` 헤더로 전달.",
                example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,

        @Schema(description = "Refresh Token. Access Token 만료 시 /api/auth/refresh 호출에 사용.",
                example = "eyJhbGciOiJIUzI1NiJ9...")
        String refreshToken,

        @Schema(description = "토큰 타입 (항상 \"Bearer\")", example = "Bearer")
        String tokenType,

        @Schema(description = "Access Token 유효 시간 (초)", example = "3600")
        long expiresIn,

        @Schema(description = "Refresh Token 유효 시간 (초)", example = "1209600")
        long refreshExpiresIn
) {
    public static TokenResponse bearer(
            String accessToken, long expiresIn,
            String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, refreshExpiresIn);
    }
}
