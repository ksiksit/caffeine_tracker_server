package com.jongbeom.server.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record SignupRequest(
        @Schema(description = "가입할 이메일 주소", example = "user@example.com")
        @NotBlank @Email @Size(max = 255) String email,

        @Schema(description = "비밀번호 (8~72자)", example = "P@ssw0rd123", format = "password")
        @NotBlank @Size(min = 8, max = 72) String password,

        @Schema(description = "닉네임 (2~20자)", example = "카페인러버")
        @NotBlank @Size(min = 2, max = 20) String nickname
) {
}
