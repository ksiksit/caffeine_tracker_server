package com.jongbeom.server.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.jongbeom.server.domain.calc.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.SleepMerger.Sample;
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
        // inBed(22-06)는 더 세분화된 단계에 덮이고, 덮이지 않은 앞부분(22:00-22:30)만 남는다
        assertThat(merged).containsExactly(
                new Interval(time(22), time(22, 30), 0),
                new Interval(time(22, 30), time(23, 45), 3),
                new Interval(time(23, 45), time(0, 30), 5),
                new Interval(time(0, 30), time(2), 4),
                new Interval(time(2), time(4), 3),
                new Interval(time(4), time(4, 15), 2),
                new Interval(time(4, 15), time(6), 3));
    }

    @Test
    void unsortedInput_sameResultAsSorted() {
        List<Sample> sorted = List.of(
                new Sample(time(22), time(7), 0),
                new Sample(time(23), time(1), 3),
                new Sample(time(1), time(2), 4));
        List<Sample> reversed = List.of(sorted.get(2), sorted.get(1), sorted.get(0));
        assertThat(SleepMerger.merge(reversed)).isEqualTo(SleepMerger.merge(sorted));
    }

    @Test
    void equalPriority_firstSampleInInputWins() {
        // 알 수 없는 hkValue(99)는 asleepUnspecified(1)와 같은 우선순위 → 입력 순서상 먼저 온 쪽이 이긴다(iOS 동일)
        List<Interval> unknownFirst = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 99),
                new Sample(time(22), time(23), 1)));
        List<Interval> unspecifiedFirst = SleepMerger.merge(List.of(
                new Sample(time(22), time(23), 1),
                new Sample(time(22), time(23), 99)));
        assertThat(unknownFirst).containsExactly(new Interval(time(22), time(23), 99));
        assertThat(unspecifiedFirst).containsExactly(new Interval(time(22), time(23), 1));
    }

    @Test
    void overlappingSameStage_mergedIntoOne() {
        // 같은 단계가 겹치면 경계점으로 쪼개진 뒤 다시 이어붙여 하나가 된다
        List<Interval> merged = SleepMerger.merge(List.of(
                new Sample(time(22), time(0), 3),
                new Sample(time(23), time(1), 3)));
        assertThat(merged).containsExactly(new Interval(time(22), time(1), 3));
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
