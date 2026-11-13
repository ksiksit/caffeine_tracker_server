package com.jongbeom.server.domain.insight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 기간별 섭취 통계. 날짜는 섭취일(로컬 05:00 경계). 요약 규칙은 api.md 인사이트 절. */
public record IntakeStatsResponse(
        LocalDate from,
        LocalDate to,
        int dayCount,
        int recordedDayCount,
        Double averageDailyMg,
        Double maxDailyMg,
        int overLimitDayCount,
        Integer averageLastIntakeMinuteOfDay,
        String summarySentence,
        List<DayItem> days
) {
    public record DayItem(
            LocalDate date,
            double totalMg,
            int recordCount,
            Instant firstIntakeAt,
            Instant lastIntakeAt,
            boolean overLimit
    ) {
    }
}
