package com.jongbeom.server.domain.caffeine.dto;

import java.time.Instant;
import java.util.List;

/**
 * 홈 화면이 한 번에 필요로 하는 서버 계산 결과 (← iOS HomeViewModel 상태 전체).
 * 차트 시계열 + 현재/취침 잔량 + 마감시각을 서버가 settings 를 읽어 계산해 반환한다.
 */
public record CaffeineTodayResponse(
        Instant now,
        Instant bedtime,
        List<CaffeineRecordResponse> records,
        int todayTotal,
        boolean overDailyLimit,
        List<DataPointResponse> chart,
        double currentResidual,
        double predictedAtBedtime,
        CutoffResponse cutoff,
        double effectiveHalfLifeHours,
        int referenceDoseMg
) {
    public record DataPointResponse(
            Instant time,
            double concentrationMg
    ) {
    }

    public record CutoffResponse(
            String status,
            Instant cutoff,
            Double existingAtBedtime
    ) {
    }
}
