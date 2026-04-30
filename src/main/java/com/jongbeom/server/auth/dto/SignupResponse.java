package com.jongbeom.server.auth.dto;

public record SignupResponse(
        Long userId,
        String email,
        String nickname
) {
}
