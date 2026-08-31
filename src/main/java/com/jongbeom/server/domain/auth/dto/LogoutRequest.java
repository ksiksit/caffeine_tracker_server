package com.jongbeom.server.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그아웃 요청")
public record LogoutRequest(
        @Schema(description = "현재 보유한 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9...")
        @NotBlank String refreshToken
) {
}
