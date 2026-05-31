package com.jongbeom.server.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.calc.SleepMerger.Interval;
import com.jongbeom.server.calc.SleepSummaryCalc.Result;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** iOS {@code SleepDataSOLTests.swift} 골든값 복제 (SleepSummary.sleepOnsetLatency). */
class SleepSummaryCalcTest {

    private static final Instant BASE = Instant.parse("2026-06-01T22:00:00Z"); // 22:00 가상 기준

    private static Instant t(int hour, int minute) {
        int off = (hour >= 22) ? (hour - 22) * 60 + minute : (hour + 2) * 60 + minute;
        return BASE.plusSeconds(off * 60L);
    }

    private static Interval iv(int value, int sh, int sm, int eh, int em) {
        return new Interval(t(sh, sm), t(eh, em), value);
    }

    private static Double sol(List<Interval> intervals, Instant bedtimeStart) {
        return SleepSummaryCalc.compute(intervals, bedtimeStart).sleepOnsetLatencySeconds();
    }

    @Test
    void normalSequence_inBedThenCore_returns30min() {
        Double s = sol(List.of(iv(0, 22, 0, 22, 30), iv(3, 22, 30, 23, 30), iv(4, 23, 30, 1, 0)), null);
        assertThat(s).isEqualTo(30 * 60.0);
    }

    @Test
    void longLatency_45min() {
        assertThat(sol(List.of(iv(0, 23, 0, 23, 45), iv(3, 23, 45, 2, 0)), null)).isEqualTo(45 * 60.0);
    }

    @Test
    void zeroLatency_inBedAndAsleepStartTogether() {
        assertThat(sol(List.of(iv(0, 22, 0, 22, 0), iv(5, 22, 0, 5, 0)), null)).isEqualTo(0.0);
    }

    @Test
    void bedtimeStartFallback_noMergedInBed_returns30min() {
        Double s = sol(List.of(iv(3, 22, 30, 23, 30), iv(4, 23, 30, 1, 0)), t(22, 0));
        assertThat(s).isEqualTo(30 * 60.0);
    }

    @Test
    void noInBed_returnsNil() {
        assertThat(sol(List.of(iv(3, 22, 30, 23, 30), iv(4, 23, 30, 1, 0)), null)).isNull();
    }

    @Test
    void onlyInBed_noAsleep_returnsNil() {
        assertThat(sol(List.of(iv(0, 22, 0, 23, 0), iv(2, 23, 0, 23, 30)), null)).isNull();
    }

    @Test
    void emptyRecords_returnsNil() {
        assertThat(sol(List.of(), null)).isNull();
    }

    @Test
    void multipleInBed_usesEarliestStart() {
        Double s = sol(List.of(iv(0, 23, 0, 23, 15), iv(0, 22, 0, 22, 30), iv(3, 23, 0, 4, 0)), null);
        assertThat(s).isEqualTo(60 * 60.0);
    }

    @Test
    void multipleAsleepStages_usesEarliestAsleepStart() {
        Double s = sol(List.of(iv(0, 22, 0, 22, 20), iv(5, 23, 0, 23, 30), iv(3, 22, 20, 23, 0)), null);
        assertThat(s).isEqualTo(20 * 60.0);
    }

    @Test
    void asleepBeforeInBed_negativeLatency_returnsNil() {
        assertThat(sol(List.of(iv(3, 22, 0, 23, 0), iv(0, 22, 30, 22, 45)), null)).isNull();
    }

    @Test
    void awakeStageNotCountedAsAsleep() {
        Double s = sol(List.of(iv(0, 22, 0, 22, 30), iv(2, 22, 30, 23, 0), iv(3, 23, 0, 5, 0)), null);
        assertThat(s).isEqualTo(60 * 60.0);
    }

    @Test
    void unspecifiedAsleepCounted() {
        assertThat(sol(List.of(iv(0, 22, 0, 22, 10), iv(1, 22, 10, 5, 0)), null)).isEqualTo(10 * 60.0);
    }

    @Test
    void aggregateMetrics_totalSleepEfficiencyBreakdown() {
        // inBed 22:00-22:30(30m), core 22:30-23:30(60m), deep 23:30-01:00(90m)
        Result r = SleepSummaryCalc.compute(
                List.of(iv(0, 22, 0, 22, 30), iv(3, 22, 30, 23, 30), iv(4, 23, 30, 1, 0)), null);
        assertThat(r.totalSleepSeconds()).isEqualTo((60 + 90) * 60.0); // 수면=core+deep=150m
        assertThat(r.timeInBedSeconds()).isEqualTo((30 + 60 + 90) * 60.0); // 침대=180m
        assertThat(r.sleepEfficiency()).isCloseTo(150.0 / 180.0, within(1e-9));
        assertThat(r.stageBreakdown()).hasSize(3); // inBed, core, deep
        assertThat(r.stageBreakdown().get(0).stage()).isEqualTo(SleepStage.IN_BED); // allCases 순서
    }
}
