package com.jongbeom.server.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.jongbeom.server.calc.SleepMerger.Interval;
import com.jongbeom.server.calc.SleepMerger.Sample;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** iOS {@code SleepDataProcessorTests.swift} 골든값 복제. */
class SleepMergerTest {

    /** 18:00 기준 분 오프셋 → Instant. (← Swift time(hour) 헬퍼와 동일 의미) */
    private static final Instant BASE = Instant.parse("2026-06-01T18:00:00Z");

    /**
     * 밤 시나리오용 벽시계 → Instant 변환. 18~23시는 당일 저녁, 0~17시는 다음날 새벽으로 해석한다.
     * 예: time(22) = 06-01T22:00Z, time(1) = 06-02T01:00Z.
     */
    private static Instant time(int hour, int minute) {
        int minutesFrom18 = (hour >= 18) ? (hour - 18) * 60 + minute : (hour + 6) * 60 + minute;
        return BASE.plusSeconds(minutesFrom18 * 60L);
    }

    private static Instant time(int hour) {
        return time(hour, 0);
    }

    private static Sample sample(int startHour, int startMinute, int endHour, int endMinute, int hkValue) {
        return new Sample(time(startHour, startMinute), time(endHour, endMinute), hkValue);
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(SleepMerger.merge(List.of())).isEmpty();
    }

    @Test
    void singleSample_returnsSame() {
        List<Interval> merged = SleepMerger.merge(List.of(new Sample(time(22), time(23), 3)));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).start()).isEqualTo(time(22));
        assertThat(merged.get(0).end()).isEqualTo(time(23));
        assertThat(merged.get(0).hkValue()).isEqualTo(3);
    }

    @Test
    void adjacentNoOverlap_keepsAll() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 3),
                new Sample(time(23), time(0), 4)));
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).hkValue()).isEqualTo(3);
        assertThat(merged.get(1).hkValue()).isEqualTo(4);
    }

    @Test
    void adjacentSameStage_merged() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 3),
                new Sample(time(23), time(0), 3)));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).start()).isEqualTo(time(22));
        assertThat(merged.get(0).end()).isEqualTo(time(0));
        assertThat(merged.get(0).hkValue()).isEqualTo(3);
    }

    @Test
    void fullOverlap_keepsHigherPriority() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 0),  // inBed
                new Sample(time(22), time(23), 5))); // rem (hkValue 5)
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).hkValue()).isEqualTo(5);
    }

    @Test
    void partialOverlap_preservesNonOverlappingPortion() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(7), 0),  // inBed 22-07
                new Sample(time(23), time(1), 3))); // core 23-01
        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).start()).isEqualTo(time(22));
        assertThat(merged.get(0).end()).isEqualTo(time(23));
        assertThat(merged.get(0).hkValue()).isEqualTo(0);
        assertThat(merged.get(1).start()).isEqualTo(time(23));
        assertThat(merged.get(1).end()).isEqualTo(time(1));
        assertThat(merged.get(1).hkValue()).isEqualTo(3);
        assertThat(merged.get(2).start()).isEqualTo(time(1));
        assertThat(merged.get(2).end()).isEqualTo(time(7));
        assertThat(merged.get(2).hkValue()).isEqualTo(0);
    }

    @Test
    void iPhoneWatchRealisticScenario() {
        List<Interval> merged = SleepMerger.merge(List.of(
                sample(22, 0, 6, 0, 0),
                sample(22, 30, 23, 45, 3),
                sample(23, 45, 0, 30, 5),
                sample(0, 30, 2, 0, 4),
                sample(2, 0, 4, 0, 3),
                sample(4, 0, 4, 15, 2),
                sample(4, 15, 6, 0, 3)));
        assertThat(merged).hasSize(7);
        assertThat(merged.get(0).start()).isEqualTo(time(22));
        assertThat(merged.get(0).end()).isEqualTo(time(22, 30));
        assertThat(merged.get(0).hkValue()).isEqualTo(0);
        assertThat(merged.get(1).hkValue()).isEqualTo(3);
        assertThat(merged.get(2).start()).isEqualTo(time(23, 45));
        assertThat(merged.get(2).end()).isEqualTo(time(0, 30));
        assertThat(merged.get(2).hkValue()).isEqualTo(5);
    }

    @Test
    void gapBetweenSamples_gapPreserved() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 3),
                new Sample(time(1), time(2), 4)));
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).end()).isEqualTo(time(23));
        assertThat(merged.get(1).start()).isEqualTo(time(1));
    }

    @Test
    void tripleOverlap_highestPriorityWins() {
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 0),
                new Sample(time(22), time(23), 3),
                new Sample(time(22), time(23), 5)));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).hkValue()).isEqualTo(5);
    }

    @Test
    void stagePriority_correctOrder() {
        assertThat(SleepStage.stagePriority(4)).isGreaterThan(SleepStage.stagePriority(5)); // deep>rem
        assertThat(SleepStage.stagePriority(5)).isGreaterThan(SleepStage.stagePriority(3)); // rem>core
        assertThat(SleepStage.stagePriority(3)).isGreaterThan(SleepStage.stagePriority(2)); // core>awake
        assertThat(SleepStage.stagePriority(2)).isGreaterThan(SleepStage.stagePriority(0)); // awake>inBed
    }
}
