package com.jongbeom.server.domain.user.dto;

public record MeResponse(
        Long userId,
        String email
) {
}
