package com.jongbeom.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 평소 첫 기록 시각(일별 첫 기록의 05:00 기준 오프셋 중앙값). 하루 경계 05:00, 05:00 이전은 전날. */
class UsualFirstRecordTimeTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static Instant kst(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant();
    }

    @Test
    void median_odd_returnsMiddle() {
        // 09:00, 10:00, 12:00 → 오프셋 4h, 5h, 7h → 중앙값 5h
        Optional<Duration> median = UsualFirstRecordTime.median(List.of(
                kst("2026-05-29T12:00:00"), kst("2026-05-30T09:00:00"), kst("2026-05-31T10:00:00")), SEOUL, 3);
        assertThat(median).contains(Duration.ofHours(5));
    }

    @Test
    void median_even_averagesMiddleTwo() {
        // 4,4,4,6,6,6h → (4+6)/2 = 5h
        Optional<Duration> median = UsualFirstRecordTime.median(List.of(
                kst("2026-05-26T09:00:00"), kst("2026-05-27T09:00:00"), kst("2026-05-28T09:00:00"),
                kst("2026-05-29T11:00:00"), kst("2026-05-30T11:00:00"), kst("2026-05-31T11:00:00")), SEOUL, 6);
        assertThat(median).contains(Duration.ofHours(5));
    }

    @Test
    void median_sameDay_usesFirstRecordOnly() {
        // 같은 날 09:00·15:00 → 그날 오프셋 4h 하나, 날 수 1
        Optional<Duration> median = UsualFirstRecordTime.median(List.of(
                kst("2026-05-31T15:00:00"), kst("2026-05-31T09:00:00")), SEOUL, 1);
        assertThat(median).contains(Duration.ofHours(4));
    }

    @Test
    void median_before5am_belongsToPreviousDay() {
        // 05-30 02:00 은 05-29 의 하루(05:00 경계) → 오프셋 21h
        Optional<Duration> median = UsualFirstRecordTime.median(List.of(kst("2026-05-30T02:00:00")), SEOUL, 1);
        assertThat(median).contains(Duration.ofHours(21));
    }

    @Test
    void median_at5am_isZeroOffset() {
        assertThat(UsualFirstRecordTime.median(List.of(kst("2026-05-31T05:00:00")), SEOUL, 1))
                .contains(Duration.ZERO);
    }

    @Test
    void median_fewerDaysThanMin_isEmpty() {
        List<Instant> fourDays = List.of(kst("2026-05-28T09:00:00"), kst("2026-05-29T09:00:00"),
                kst("2026-05-30T09:00:00"), kst("2026-05-31T09:00:00"));
        assertThat(UsualFirstRecordTime.median(fourDays, SEOUL, 5)).isEmpty();
        assertThat(UsualFirstRecordTime.median(List.of(), SEOUL, 0)).isEmpty();
    }
}
