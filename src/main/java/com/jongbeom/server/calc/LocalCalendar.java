package com.jongbeom.server.calc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 타임존 종속 로컬 달력 연산. iOS {@code Calendar.current}(기기 로컬 TZ) 의미를 서버에서 재현한다.
 *
 * <p>절대시각은 {@link Instant}(UTC)로 저장하되, "오늘 새벽 경계 / 취침시각" 같은 *로컬 달력* 계산만
 * 클라가 전달한 IANA 타임존으로 변환한다. 이렇게 하지 않으면 잔량/마감/학습 경계가 어긋난다.
 */
public final class LocalCalendar {

    private LocalCalendar() {
    }

    /** 차트 시작 = now 기준 로컬 자정 + chartStartHour(5시). (← Swift filterTodayRecords / updateChartData start) */
    public static Instant chartStart(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return today.atStartOfDay(zone).plusHours(Pharmacokinetics.CHART_START_HOUR).toInstant();
    }

    /** 차트 끝 = 차트 시작 + chartForwardHours(24h). */
    public static Instant chartEnd(Instant now, ZoneId zone) {
        return chartStart(now, zone).plus(java.time.Duration.ofHours(Pharmacokinetics.CHART_FORWARD_HOURS));
    }

    /** 수면 조회 윈도우 시작/끝 (← iOS AppConstants.Sleep windowStartHour=18, windowEndHour=12). */
    public static final int SLEEP_WINDOW_START_HOUR = 18;
    public static final int SLEEP_WINDOW_END_HOUR = 12;

    /** date 기준 수면 윈도우 시작 = 전날 18:00 로컬. (← SleepFetchService.fetchSleepSamples) */
    public static Instant sleepWindowStart(LocalDate date, ZoneId zone) {
        return date.minusDays(1).atStartOfDay(zone).withHour(SLEEP_WINDOW_START_HOUR).toInstant();
    }

    /** date 기준 수면 윈도우 끝 = 당일 12:00 로컬. */
    public static Instant sleepWindowEnd(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).withHour(SLEEP_WINDOW_END_HOUR).toInstant();
    }

    /**
     * now 기준 "다음 취침시각". 오늘 날짜의 hour:minute, 이미 지났으면 다음날.
     * (← Swift HomeViewModel.nextBedtime)
     */
    public static Instant nextBedtime(Instant now, ZoneId zone, int hour, int minute) {
        ZonedDateTime zNow = now.atZone(zone);
        ZonedDateTime todayBedtime = zNow.toLocalDate()
                .atStartOfDay(zone)
                .withHour(hour)
                .withMinute(minute);
        if (!todayBedtime.isAfter(zNow)) {
            todayBedtime = todayBedtime.plusDays(1);
        }
        return todayBedtime.toInstant();
    }
}
