package com.jongbeom.server.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 로컬 달력 경계: 차트(05:00 시작·24h), 수면 윈도우(전날 18:00~당일 12:00), 다음 취침시각. */
class LocalCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Instant kst(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+09:00").toInstant();
    }

    // ---- chartStart / chartEnd ----

    @Test
    void chartStart_isLocalFiveAmOfNowsDate() {
        assertThat(LocalCalendar.chartStart(kst("2026-06-01T14:00:00"), KST))
                .isEqualTo(kst("2026-06-01T05:00:00"));
    }

    @Test
    void chartStart_nowBeforeFiveAm_returnsSameDayFiveAm() {
        // 현재 동작 고정: 05:00 이전의 now 는 그날 05:00(now 보다 미래)을 반환한다.
        // UsualFirstRecordTime 은 05:00 이전을 "전날"로 묶는다 — 두 규칙이 다르다는 점을 여기서 드러내 둔다.
        assertThat(LocalCalendar.chartStart(kst("2026-06-01T02:00:00"), KST))
                .isEqualTo(kst("2026-06-01T05:00:00"));
    }

    @Test
    void chartStart_usesZoneLocalDate_notUtcDate() {
        // UTC 로는 5/31 이지만 KST 로는 6/1 아침 → 6/1 05:00 KST
        Instant now = Instant.parse("2026-05-31T23:30:00Z"); // = 06-01 08:30 KST
        assertThat(LocalCalendar.chartStart(now, KST)).isEqualTo(kst("2026-06-01T05:00:00"));
    }

    @Test
    void chartEnd_isChartStartPlus24h() {
        assertThat(LocalCalendar.chartEnd(kst("2026-06-01T14:00:00"), KST))
                .isEqualTo(kst("2026-06-02T05:00:00"));
    }

    @Test
    void chartStart_onDstStartDay_addsFiveElapsedHoursFromMidnight() {
        // 현재 동작 고정(iOS 골든 일치 목적, LocalCalendar 주석 참조): 자정 + 경과 5시간.
        // 2026-03-08 뉴욕은 02:00 EST → 03:00 EDT 로 1시간이 사라지므로 벽시계 05:00 이 아니라 06:00 EDT.
        Instant now = OffsetDateTime.parse("2026-03-08T12:00:00-04:00").toInstant();
        assertThat(LocalCalendar.chartStart(now, NEW_YORK))
                .isEqualTo(OffsetDateTime.parse("2026-03-08T06:00:00-04:00").toInstant());
    }

    // ---- sleepWindowStart / sleepWindowEnd ----

    @Test
    void sleepWindow_isPreviousDay18ToSameDay12() {
        LocalDate morning = LocalDate.of(2026, 6, 2);
        assertThat(LocalCalendar.sleepWindowStart(morning, KST)).isEqualTo(kst("2026-06-01T18:00:00"));
        assertThat(LocalCalendar.sleepWindowEnd(morning, KST)).isEqualTo(kst("2026-06-02T12:00:00"));
    }

    @Test
    void sleepWindowStart_onDstStartDay_isWallClock18() {
        // withHour 방식은 벽시계 시각을 지정한다 — chartStart(경과시간 방식)와 대비
        LocalDate morning = LocalDate.of(2026, 3, 9);
        assertThat(LocalCalendar.sleepWindowStart(morning, NEW_YORK))
                .isEqualTo(OffsetDateTime.parse("2026-03-08T18:00:00-04:00").toInstant());
    }

    // ---- nextBedtime ----

    @Test
    void nextBedtime_laterToday() {
        assertThat(LocalCalendar.nextBedtime(kst("2026-06-01T14:00:00"), KST, 23, 0))
                .isEqualTo(kst("2026-06-01T23:00:00"));
    }

    @Test
    void nextBedtime_alreadyPassed_tomorrow() {
        assertThat(LocalCalendar.nextBedtime(kst("2026-06-01T23:30:00"), KST, 23, 0))
                .isEqualTo(kst("2026-06-02T23:00:00"));
    }

    @Test
    void nextBedtime_exactlyNow_tomorrow() {
        // "이미 지남"은 !isAfter 로 판정 — 정각이면 다음날
        assertThat(LocalCalendar.nextBedtime(kst("2026-06-01T23:00:00"), KST, 23, 0))
                .isEqualTo(kst("2026-06-02T23:00:00"));
    }

    @Test
    void nextBedtime_afterMidnightBedtime() {
        // 취침 01:30, 지금 23:00 → 오늘 01:30 은 지났으므로 내일 01:30
        assertThat(LocalCalendar.nextBedtime(kst("2026-06-01T23:00:00"), KST, 1, 30))
                .isEqualTo(kst("2026-06-02T01:30:00"));
    }
}
