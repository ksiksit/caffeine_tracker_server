package com.jongbeom.server.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "토큰 갱신 요청")
public record RefreshTokenRequest(
        @Schema(description = "발급받은 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9...")
        @NotBlank String refreshToken
) {
}
