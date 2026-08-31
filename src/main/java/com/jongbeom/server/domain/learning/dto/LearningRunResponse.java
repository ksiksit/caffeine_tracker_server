package com.jongbeom.server.domain.learning.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "학습 실행 결과")
public record LearningRunResponse(
        @Schema(description = "이번에 새로 학습된 관측 수", example = "1") int updatedCount,
        @Schema(description = "건너뛴 사유 코드(없으면 null)", example = "ALREADY_LEARNED") String skipReason,
        @Schema(description = "건너뛴 사유 메시지(없으면 null)") String skipMessage
) {
}
