package com.jongbeom.server.calc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 타임존 종속 로컬 달력 연산. iOS {@code Calendar.current}(기기 로컬 TZ) 의미를 서버에서 재현한다.
 *
 * <p>절대시각은 {@link Instant}(UTC)로 저장하되, "오늘 새벽 경계 / 취침시각" 같은 *로컬 달력* 계산만
 * 클라가 전달한 IANA 타임존으로 변환한다. 이렇게 하지 않으면 잔량/마감/학습 경계가 어긋난다.
 *
 * <p>주의: {@link #chartStart}는 자정에 {@code plusHours}를 더하고, 나머지 메서드는 {@code withHour}로
 * 시각을 지정한다. 두 방식은 DST 전환일에 결과가 달라질 수 있으나 iOS 원본과의 골든 일치를 위해
 * 각각 그대로 유지한다 — 통일 금지.
 */
public final class LocalCalendar {

    private LocalCalendar() {
    }

    /** 수면 조회 윈도우 시작/끝 시각, 로컬 벽시계 기준 (← iOS AppConstants.Sleep windowStartHour=18, windowEndHour=12). */
    public static final int SLEEP_WINDOW_START_HOUR = 18;
    public static final int SLEEP_WINDOW_END_HOUR = 12;

    /**
     * 차트 시작 = now가 속한 zone 로컬 날짜의 자정 + {@link Pharmacokinetics#CHART_START_HOUR}(5시).
     * (← Swift filterTodayRecords / updateChartData start)
     */
    public static Instant chartStart(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return today.atStartOfDay(zone).plusHours(Pharmacokinetics.CHART_START_HOUR).toInstant();
    }

    /** 차트 끝 = 차트 시작 + {@link Pharmacokinetics#CHART_FORWARD_HOURS}(24h). */
    public static Instant chartEnd(Instant now, ZoneId zone) {
        return chartStart(now, zone).plus(Duration.ofHours(Pharmacokinetics.CHART_FORWARD_HOURS));
    }

    /**
     * date(수면 요약이 끝나는 아침 날짜) 기준 수면 윈도우 시작 = 전날 {@value #SLEEP_WINDOW_START_HOUR}:00 로컬.
     * (← SleepFetchService.fetchSleepSamples)
     */
    public static Instant sleepWindowStart(LocalDate date, ZoneId zone) {
        return date.minusDays(1).atStartOfDay(zone).withHour(SLEEP_WINDOW_START_HOUR).toInstant();
    }

    /** date 기준 수면 윈도우 끝 = 당일 {@value #SLEEP_WINDOW_END_HOUR}:00 로컬. */
    public static Instant sleepWindowEnd(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).withHour(SLEEP_WINDOW_END_HOUR).toInstant();
    }

    /**
     * now 기준 "다음 취침시각". 오늘 날짜의 hour:minute(로컬 벽시계), 이미 지났으면 다음날.
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
