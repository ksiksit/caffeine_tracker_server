package com.jongbeom.server.domain.caffeine.dto;

import com.jongbeom.server.domain.caffeine.entity.CaffeineRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "카페인 기록")
public record CaffeineRecordResponse(
        @Schema(description = "기록 ID", example = "1") Long id,
        @Schema(description = "카페인 함량(mg)", example = "150") int amount,
        @Schema(description = "음료명", example = "아메리카노") String drinkName,
        @Schema(description = "섭취 시각(UTC)", example = "2026-06-01T05:30:00Z") Instant timestamp
) {
    public static CaffeineRecordResponse from(CaffeineRecord r) {
        return new CaffeineRecordResponse(r.getId(), r.getAmount(), r.getDrinkName(), r.getTimestamp());
    }
}
