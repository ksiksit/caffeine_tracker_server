package com.jongbeom.server.domain.insight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 카페인·수면 상관 리포트. 날짜는 밤이 끝나는 아침 날짜. sufficient 가 false 여도 모든 필드를 채우고 nights 는 항상 내린다.
 * 비교 기준·문장 규칙은 api.md 인사이트 절.
 */
public record CaffeineSleepReportResponse(
        LocalDate from,
        LocalDate to,
        int nightCount,
        boolean sufficient,
        double residualThresholdMg,
        double halfLifeHoursUsed,
        Comparison residualComparison,
        Comparison cutoffComparison,
        List<String> headlines,
        List<NightItem> nights
) {
    public record Comparison(
            Group high,
            Group low,
            boolean sufficient,
            Integer moreNightsNeeded,
            Double solDiffMinutes,
            Double efficiencyDiff,
            String sentence
    ) {
    }

    public record Group(
            int nightCount,
            int solNightCount,
            Double avgSolMinutes,
            Double avgSleepEfficiency,
            Double avgTotalSleepMinutes,
            Double avgDeepSleepRatio
    ) {
    }

    public record NightItem(
            LocalDate date,
            Instant bedtime,
            double residualAtBedtimeMg,
            double dayTotalMg,
            Instant lastIntakeAt,
            Boolean cutoffKept,
            Double solMinutes,
            double sleepEfficiency,
            double totalSleepMinutes,
            Double deepSleepRatio
    ) {
    }
}
