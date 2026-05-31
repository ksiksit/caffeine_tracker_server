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
