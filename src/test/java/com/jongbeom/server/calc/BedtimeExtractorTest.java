package com.jongbeom.server.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.calc.BedtimeExtractor.Gate;
import com.jongbeom.server.calc.BedtimeExtractor.Result;
import com.jongbeom.server.calc.SleepMerger.Interval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** iOS BayesianHalfLifeLearnerTests의 bedtime/SOL 게이트 시나리오 복제. */
class BedtimeExtractorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MORNING = LocalDate.of(2026, 4, 21);

    private static Instant kst(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+09:00").toInstant();
    }

    private static Interval interval(int hkValue, String start, String end) {
        return new Interval(kst(start), kst(end), hkValue);
    }

    @Test
    void normal_withInBed_returnsSOL30() {
        List<Interval> records = List.of(
                interval(0, "2026-04-20T23:00:00", "2026-04-20T23:30:00"),  // inBed
                interval(3, "2026-04-20T23:30:00", "2026-04-21T07:30:00")); // core 8h
        Result result = BedtimeExtractor.extract(records, null, 23, 0, MORNING, KST);
        assertThat(result.isOk()).isTrue();
        assertThat(result.solMinutes()).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void sleepTooShort_2h_skip() {
        List<Interval> records = List.of(
                interval(0, "2026-04-20T23:00:00", "2026-04-20T23:30:00"),
                interval(3, "2026-04-20T23:30:00", "2026-04-21T01:30:00")); // core 2h
        Result result = BedtimeExtractor.extract(records, null, 23, 0, MORNING, KST);
        assertThat(result.failure()).isEqualTo(Gate.SLEEP_TOO_SHORT);
    }

    @Test
    void usesConfiguredBedtime_whenInBedMissing() {
        // inBed 없음 → 설정 취침시각(23:00) 폴백. core 23:30 시작 → SOL 30분.
        List<Interval> records = List.of(
                interval(3, "2026-04-20T23:30:00", "2026-04-21T07:30:00"));
        Result result = BedtimeExtractor.extract(records, null, 23, 0, MORNING, KST);
        assertThat(result.isOk()).isTrue();
        assertThat(result.solMinutes()).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void solTooLarge_skip() {
        // inBed 22:00, asleep 02:00 → SOL 240분 > 180
        List<Interval> records = List.of(
                interval(0, "2026-04-20T22:00:00", "2026-04-21T02:00:00"),
                interval(3, "2026-04-21T02:00:00", "2026-04-21T10:00:00")); // core 8h
        Result result = BedtimeExtractor.extract(records, null, 23, 0, MORNING, KST);
        assertThat(result.failure()).isEqualTo(Gate.INVALID_SOL);
    }

    @Test
    void rawBedtimeStart_takesPrecedence() {
        // 원시 bedtimeStart 제공 시 그것을 사용(22:50) → SOL 40분
        List<Interval> records = List.of(
                interval(0, "2026-04-20T23:00:00", "2026-04-20T23:30:00"),
                interval(3, "2026-04-20T23:30:00", "2026-04-21T07:30:00"));
        Result result = BedtimeExtractor.extract(records, kst("2026-04-20T22:50:00"), 23, 0, MORNING, KST);
        assertThat(result.isOk()).isTrue();
        assertThat(result.solMinutes()).isCloseTo(40.0, within(1e-9));
    }
}
