package com.jongbeom.server.domain.caffeine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 홈 화면이 한 번에 필요로 하는 서버 계산 결과 (← iOS HomeViewModel 상태 전체).
 * 차트 시계열 + 현재/취침 잔량 + 마감시각을 서버가 settings 를 읽어 계산해 반환한다.
 */
@Schema(description = "오늘의 카페인 현황(서버 계산)")
public record CaffeineTodayResponse(
        @Schema(description = "계산 기준 시각(에코, UTC)") Instant now,
        @Schema(description = "다음 취침 시각(UTC)") Instant bedtime,
        @Schema(description = "오늘 기록(차트 시작 5시 이후)") List<CaffeineRecordResponse> records,
        @Schema(description = "오늘 총 섭취량(mg)", example = "300") int todayTotal,
        @Schema(description = "일일 권장량(400mg) 초과 여부") boolean overDailyLimit,
        @Schema(description = "15분 간격 잔류량 시계열") List<DataPointResponse> chart,
        @Schema(description = "현재 잔류량(mg)", example = "120.5") double currentResidual,
        @Schema(description = "취침 시 예상 잔류량(mg)", example = "20.3") double predictedAtBedtime,
        @Schema(description = "마감시각 계산 결과") CutoffResponse cutoff,
        @Schema(description = "계산에 사용된 실제 반감기(h)", example = "5.0") double effectiveHalfLifeHours,
        @Schema(description = "마감시각 기준 용량(mg)", example = "75") int referenceDoseMg
) {
    @Schema(description = "잔류량 시계열 점")
    public record DataPointResponse(
            @Schema(description = "시각(UTC)") Instant time,
            @Schema(description = "잔류량(mg)") double concentrationMg
    ) {
    }

    @Schema(description = "마감시각 결과. status: SAFE_ANYTIME(언제나 가능) / CUTOFF(cutoff 까지) / ALREADY_EXCEEDED(이미 초과)")
    public record CutoffResponse(
            @Schema(description = "SAFE_ANYTIME | CUTOFF | ALREADY_EXCEEDED") String status,
            @Schema(description = "마감 시각(status=CUTOFF 일 때, UTC)") Instant cutoff,
            @Schema(description = "취침 시 잔량(status=ALREADY_EXCEEDED 일 때, mg)") Double existingAtBedtime
    ) {
    }
}
