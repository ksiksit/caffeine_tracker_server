package com.jongbeom.server.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 응답")
public record SignupResponse(
        @Schema(description = "생성된 사용자 ID", example = "1") Long userId,
        @Schema(description = "사용자 이메일", example = "user@example.com") String email,
        @Schema(description = "사용자 닉네임", example = "카페인러버") String nickname
) {
}
