package com.jongbeom.server.sleep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 서버가 원시 샘플을 병합·계산한 수면 요약 (← iOS SleepSummary).
 * stage 는 hkValue 로 전달하고 클라가 SleepStage 로 복원한다.
 */
@Schema(description = "수면 요약(서버 병합·계산)")
public record SleepSummaryResponse(
        @Schema(description = "대상 날짜(아침 기준)") LocalDate date,
        @Schema(description = "수면 데이터 존재 여부") boolean hasData,
        @Schema(description = "취침 시작(최초 inBed, UTC, 없으면 null)") Instant bedtimeStart,
        @Schema(description = "병합된 수면 구간") List<RecordItem> records,
        @Schema(description = "총 수면 시간(초)") double totalSleepSeconds,
        @Schema(description = "침대 시간(초)") double timeInBedSeconds,
        @Schema(description = "수면 효율(0~1)") double sleepEfficiency,
        @Schema(description = "수면 잠복기(초, 없으면 null)") Double sleepOnsetLatencySeconds,
        @Schema(description = "단계별 시간") List<StageItem> stageBreakdown
) {
    @Schema(description = "수면 구간")
    public record RecordItem(
            @Schema(description = "시작(UTC)") Instant start,
            @Schema(description = "종료(UTC)") Instant end,
            @Schema(description = "HK rawValue") int hkValue
    ) {
    }

    @Schema(description = "단계별 시간")
    public record StageItem(
            @Schema(description = "HK rawValue") int hkValue,
            @Schema(description = "시간(초)") double durationSeconds
    ) {
    }

    public static SleepSummaryResponse empty(LocalDate date) {
        return new SleepSummaryResponse(date, false, null, List.of(), 0, 0, 0, null, List.of());
    }
}
