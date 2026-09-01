package com.jongbeom.server.domain.sleep.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 서버가 원시 샘플을 병합·계산한 수면 요약 (← iOS SleepSummary).
 * stage 는 hkValue 로 전달하고 클라가 SleepStage 로 복원한다.
 */
public record SleepSummaryResponse(
        LocalDate date,
        boolean hasData,
        Instant bedtimeStart,
        List<RecordItem> records,
        double totalSleepSeconds,
        double timeInBedSeconds,
        double sleepEfficiency,
        Double sleepOnsetLatencySeconds,
        List<StageItem> stageBreakdown
) {
    public record RecordItem(
            Instant start,
            Instant end,
            int hkValue
    ) {
    }

    public record StageItem(
            int hkValue,
            double durationSeconds
    ) {
    }

    public static SleepSummaryResponse empty(LocalDate date) {
        return new SleepSummaryResponse(date, false, null, List.of(), 0, 0, 0, null, List.of());
    }
}
