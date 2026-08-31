package com.jongbeom.server.domain.caffeine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

@Schema(description = "카페인 기록 생성 요청")
public record CreateCaffeineRecordRequest(
        @Schema(description = "카페인 함량(mg)", example = "150")
        @NotNull @Min(1) @Max(10000) Integer amount,

        @Schema(description = "음료명", example = "아메리카노")
        @NotBlank @Size(max = 100) String drinkName,

        @Schema(description = "섭취 시각(ISO-8601 + 오프셋)", example = "2026-06-01T14:30:00+09:00")
        @NotNull OffsetDateTime timestamp
) {
}
