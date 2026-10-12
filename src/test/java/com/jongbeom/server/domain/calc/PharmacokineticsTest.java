package com.jongbeom.server.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.domain.calc.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.domain.calc.Pharmacokinetics.DataPoint;
import com.jongbeom.server.domain.calc.Pharmacokinetics.Dose;
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
    void remainingAmount_nonPositiveHalfLife_returnsZero() {
        assertThat(Pharmacokinetics.remainingAmount(100, 1, 0)).isZero();
        assertThat(Pharmacokinetics.remainingAmount(100, 1, -5.0)).isZero();
    }

    @Test
    void totalRemaining_superpositionAddsDoses() {
        Dose d1 = new Dose(NOW.minusSeconds(3600), 100);
        Dose d2 = new Dose(NOW.minusSeconds(1800), 100);
        double combined = Pharmacokinetics.totalRemaining(NOW, List.of(d1, d2), 5.0);
        // 골든값: 100·2^(-1/5) + 100·2^(-0.5/5) — 프로덕션(exp·ln2)과 다른 형태로 독립 산출
        assertThat(combined).isCloseTo(180.35835548329317, within(1e-9));
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

    @Test
    void generateTimeSeries_doseInRange_addsPointsJustBeforeAndAtDose() {
        // 15분 간격 틱 5개 + 섭취(00:15) 직전(00:14:59)·직후(00:15:00) 점 2개 = 7점
        Dose dose = new Dose(NOW.plusSeconds(900), 100);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);

        assertThat(points).extracting(DataPoint::time).containsExactly(
                NOW,
                NOW.plusSeconds(899),   // 섭취 1초 전
                NOW.plusSeconds(900),   // 섭취 시점(sharp transition)
                NOW.plusSeconds(900),   // 같은 시각의 정규 틱
                NOW.plusSeconds(1800),
                NOW.plusSeconds(2700),
                NOW.plusSeconds(3600));
        // 골든값: 100·2^(-t/5) (t = 섭취 후 시간)
        double[] expectedMg = {0.0, 0.0, 100.0, 100.0, 96.59363289248456, 93.30329915368074, 90.12504626108301};
        for (int i = 0; i < expectedMg.length; i++) {
            assertThat(points.get(i).concentrationMg()).as("point %d", i).isCloseTo(expectedMg[i], within(1e-9));
        }
    }

    @Test
    void generateTimeSeries_doseExactlyAtStart_noTransitionPoints() {
        // (start, end] 에 엄격히 포함된 dose 만 직전/직후 점을 만든다 — start 정각은 제외(iOS 동일)
        Dose dose = new Dose(NOW, 100);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);
        assertThat(points).hasSize(5);
        assertThat(points.get(0).concentrationMg()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void generateTimeSeries_doseAfterEnd_noTransitionPoints() {
        Dose dose = new Dose(NOW.plusSeconds(7200), 100);
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(dose), NOW, NOW.plusSeconds(3600), 15, 5.0);
        assertThat(points).hasSize(5).allMatch(p -> p.concentrationMg() == 0);
    }

    @Test
    void generateTimeSeries_nonPositiveInterval_empty() {
        assertThat(Pharmacokinetics.generateTimeSeries(List.of(), NOW, NOW.plusSeconds(3600), 0, 5.0)).isEmpty();
        assertThat(Pharmacokinetics.generateTimeSeries(List.of(), NOW, NOW.plusSeconds(3600), -15, 5.0)).isEmpty();
    }

    @Test
    void generateTimeSeries_endIsInclusive() {
        List<DataPoint> points = Pharmacokinetics.generateTimeSeries(
                List.of(), NOW, NOW.plusSeconds(1800), 15, 5.0);
        assertThat(points).extracting(DataPoint::time)
                .containsExactly(NOW, NOW.plusSeconds(900), NOW.plusSeconds(1800));
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
        // 골든값: ln(75/50)/(ln2/5) = 2.9248125036 h — 프로덕션과 같은 수식으로 재계산하면
        // 자기충족 단언이 되므로 리터럴로 고정한다.
        double expectedHoursBefore = 2.9248125036057813;
        double actualHoursBefore = (NOW.toEpochMilli() - result.cutoff().toEpochMilli()) / 3_600_000.0;
        // 허용 오차 1e-6h(≈3.6ms): cutoff 는 밀리초 절단이라 1ms(≈2.8e-7h) 미만 차이만 허용
        assertThat(actualHoursBefore).isCloseTo(expectedHoursBefore, within(1e-6));
    }

    @Test
    void cutoff_alreadyExceeded_whenBedtimeResidualOverThreshold() {
        Dose bigDose = new Dose(NOW, 100); // 취침 시 잔량 100mg >= 50mg 안전선
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(bigDose), NOW, 75, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.ALREADY_EXCEEDED);
        assertThat(result.existingAtBedtime()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void cutoff_headroomExactlyEqualsReferenceDose_safeAnytime() {
        // 기존 잔량 0, 안전선 50 → 여유 50 == 기준 용량 50 → 언제 마셔도 안전(경계 포함)
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(), NOW, 50, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.SAFE_ANYTIME);
    }

    @Test
    void cutoff_bedtimeResidualExactlyAtThreshold_alreadyExceeded() {
        // 취침 시각 정각에 50mg → 취침 잔량 50 == 안전선 → 여유 0 → 이미 초과(경계 포함)
        CutoffResult result = Pharmacokinetics.cutoffTime(List.of(new Dose(NOW, 50)), NOW, 75, 5.0, 50.0);
        assertThat(result.status()).isEqualTo(CutoffResult.Status.ALREADY_EXCEEDED);
        assertThat(result.existingAtBedtime()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void cutoff_invalidArguments_safeAnytime() {
        assertThat(Pharmacokinetics.cutoffTime(List.of(), NOW, 0, 5.0, 50.0).status())
                .isEqualTo(CutoffResult.Status.SAFE_ANYTIME);
        assertThat(Pharmacokinetics.cutoffTime(List.of(), NOW, 75, 0, 50.0).status())
                .isEqualTo(CutoffResult.Status.SAFE_ANYTIME);
    }
}
