package com.jongbeom.server.domain.auth.dto;

public record SignupResponse(
        Long userId,
        String email,
        String nickname
) {
}
