package com.jongbeom.server.domain.learning.dto;

public record LearningRunResponse(
        int updatedCount,
        String skipReason,
        String skipMessage
) {
}
