package com.jongbeom.server.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.calc.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.calc.Pharmacokinetics.DataPoint;
import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * iOS {@code CaffeineKineticsCalculatorTests.swift} 의 입력→기대값을 골든값으로 복제.
 * 포팅 정합성(부동소수 동률)이 서버 이관의 핵심이라 동일 케이스를 1:1로 옮긴다.
 */
class PharmacokineticsTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void remainingAmount_afterOneHalfLife_is50Percent() {
        assertThat(Pharmacokinetics.remainingAmount(100, 5.0, 5.0)).isCloseTo(50.0, within(0.01));
    }

    @Test
    void remainingAmount_afterTwoHalfLives_is25Percent() {
        // 200mg × 25% = 50mg
        assertThat(Pharmacokinetics.remainingAmount(200, 10.0, 5.0)).isCloseTo(50.0, within(0.01));
    }

    @Test
    void remainingAmount_atTimeZero_equalsFullDose() {
        assertThat(Pharmacokinetics.remainingAmount(150, 0, 5.0)).isCloseTo(150.0, within(0.01));
    }

    @Test
    void remainingAmount_negativeTime_returnsZero() {
        assertThat(Pharmacokinetics.remainingAmount(100, -1, 5.0)).isZero();
    }

    @Test
    void remainingAmount_zeroDose_returnsZero() {
        assertThat(Pharmacokinetics.remainingAmount(0, 1, 5.0)).isZero();
    }

    @Test
    void remainingAmount_afterManyHours_nearZero() {
        assertThat(Pharmacokinetics.remainingAmount(100, 50, 5.0)).isLessThan(0.1);
    }

    @Test
    void remainingAmount_customHalfLife() {
        double shortHl = Pharmacokinetics.remainingAmount(100, 3, 3.0);
        double longHl = Pharmacokinetics.remainingAmount(100, 3, 7.0);
        assertThat(shortHl).isCloseTo(50.0, within(0.01));
        assertThat(longHl).isGreaterThan(shortHl);
    }

    @Test
    void totalRemaining_superpositionAddsDoses() {
        Dose d1 = new Dose(NOW.minusSeconds(3600), 100);
        Dose d2 = new Dose(NOW.minusSeconds(1800), 100);
        double combined = Pharmacokinetics.totalRemaining(NOW, List.of(d1, d2), 5.0);
        double single = Pharmacokinetics.totalRemaining(NOW, List.of(d1), 5.0);
        assertThat(combined).isGreaterThan(single);
    }

    @Test
    void totalRemaining_futureDoseNotCounted() {
        Dose future = new Dose(NOW.plusSeconds(3600), 100);
        assertThat(Pharmacokinetics.totalRemaining(NOW, List.of(future), 5.0)).isZero();
    }

    @Test
    void generateTimeSeries_correctPointCount() {
        Dose dose = new Dose(NOW.minusSeconds(7200), 150);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);
        // 0,15,30,45,60분 = 5점 (범위 내 dose 없음 → 추가점 없음)
        assertThat(points).hasSize(5);
    }

    @Test
    void generateTimeSeries_emptyDoses_allZero() {
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(), NOW, NOW.plusSeconds(3600), 15, 5.0);
        assertThat(points).isNotEmpty().allMatch(p -> p.concentrationMg() == 0);
    }

    @Test
    void generateTimeSeries_isSortedByTime() {
        Dose dose = new Dose(NOW.plusSeconds(900), 100);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).time()).isAfterOrEqualTo(points.get(i - 1).time());
        }
    }

    @Test
    void generateTimeSeries_reindexesSequentially() {
        Dose dose = new Dose(NOW.plusSeconds(900), 100);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);
        for (int i = 0; i < points.size(); i++) {
            assertThat(points.get(i).index()).isEqualTo(i);
        }
    }

    // ---- cutoffTime (← CaffeineHelpers.caffeineCutoffTime) ----

    @Test
    void cutoff_safeAnytime_whenHeadroomCoversReferenceDose() {
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(), NOW, 30, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.SAFE_ANYTIME);
    }

    @Test
    void cutoff_computesTimeBeforeBedtime() {
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(), NOW, 75, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.CUTOFF);
        // hoursBeforeBedtime = ln(75/50)/(ln2/5) = 2.9248127 h
        double expectedHoursBefore = Math.log(75.0 / 50.0) / (Math.log(2) / 5.0);
        double actualHoursBefore = (NOW.toEpochMilli() - result.cutoff().toEpochMilli()) / 3_600_000.0;
        assertThat(actualHoursBefore).isCloseTo(expectedHoursBefore, within(0.001));
    }

    @Test
    void cutoff_alreadyExceeded_whenBedtimeResidualOverThreshold() {
        Dose bigDose = new Dose(NOW, 100); // 취침 시 잔량 100mg >= 50mg 안전선
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(bigDose), NOW, 75, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.ALREADY_EXCEEDED);
        assertThat(result.existingAtBedtime()).isCloseTo(100.0, within(0.01));
    }
}
