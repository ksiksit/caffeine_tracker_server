package com.jongbeom.server.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.domain.calc.BayesianHalfLifeUpdater.Linearization;
import com.jongbeom.server.domain.calc.BayesianHalfLifeUpdater.Posterior;
import com.jongbeom.server.domain.calc.Pharmacokinetics.Dose;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 정규-정규 켤레 갱신 검증 (← iOS BayesianHalfLifeLearnerTests의 closed-form/clamp/trust-region 속성).
 */
class BayesianHalfLifeUpdaterTest {

    private static final Instant BEDTIME = Instant.parse("2026-06-01T14:00:00Z");
    private static final double PRIOR_MEAN = 5.0;
    private static final double PRIOR_VAR = 2.25; // 모집단 prior 분산 1.5² (Koletzko 2021)
    /** trust region(±0.5h)을 한참 벗어난 극단 posterior — 안전장치 작동을 강제하는 센티널. */
    private static final double ABSURD_MEAN = 99.0;

    private static Dose dose(int mg, double hoursBeforeBedtime) {
        return new Dose(BEDTIME.minusMillis((long) (hoursBeforeBedtime * 3_600_000)), mg);
    }

    @Test
    void linearize_emptyDoses_null() {
        assertThat(BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(), PRIOR_MEAN, 1.0)).isNull();
    }

    @Test
    void linearize_residualBelowThreshold_null() {
        // 아주 오래된 소량 → 잔량 < 1mg
        assertThat(BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(1, 48)), PRIOR_MEAN, 1.0)).isNull();
    }

    @Test
    void doseExactlyAtBedtime_derivativeZero_posteriorEqualsPrior() {
        // dtHours=0 → cPrime=0 → 관측이 반감기에 무정보 → posterior == prior
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(100, 0)), PRIOR_MEAN, 1.0);
        assertThat(linearization).isNotNull();
        assertThat(linearization.cMu()).isCloseTo(100.0, within(1e-9));
        assertThat(linearization.cPrime()).isCloseTo(0.0, within(1e-12));

        Posterior rawPosterior = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 30);
        assertThat(rawPosterior.mean()).isCloseTo(PRIOR_MEAN, within(1e-9));
        assertThat(rawPosterior.variance()).isCloseTo(PRIOR_VAR, within(1e-9));
    }

    @Test
    void informativeObservation_reducesVariance() {
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        assertThat(linearization).isNotNull();
        assertThat(linearization.cPrime()).isGreaterThan(0); // 시간 경과 → 반감기 민감도 존재
        Posterior rawPosterior = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 30);
        assertThat(rawPosterior.variance()).isLessThan(PRIOR_VAR);
    }

    @Test
    void safetyBounds_trustRegion_limitsStep() {
        Posterior bounded = BayesianHalfLifeUpdater.applySafetyBounds(ABSURD_MEAN, 0.5, PRIOR_MEAN);
        assertThat(Math.abs(bounded.mean() - PRIOR_MEAN))
                .isLessThanOrEqualTo(BayesianHalfLifeUpdater.MAX_STEP_PER_UPDATE + 1e-9);
    }

    @Test
    void safetyBounds_clampsToValidRange() {
        // prior 6.8 + step 0.5 = 7.3 → clamp 7.0
        assertThat(BayesianHalfLifeUpdater.applySafetyBounds(ABSURD_MEAN, 0.5, 6.8).mean())
                .isEqualTo(7.0);
        // prior 3.2 - step 0.5 = 2.7 → clamp 3.0
        assertThat(BayesianHalfLifeUpdater.applySafetyBounds(-ABSURD_MEAN, 0.5, 3.2).mean())
                .isEqualTo(3.0);
    }

    @Test
    void safetyBounds_varianceFloor() {
        assertThat(BayesianHalfLifeUpdater.applySafetyBounds(5.0, 0.0001, PRIOR_MEAN).variance())
                .isEqualTo(BayesianHalfLifeUpdater.VARIANCE_FLOOR);
    }

    @Test
    void multiplier_affectsResidual() {
        // 흡연(×0.5) → 대사 빠름 → 같은 doses라도 취침 잔량 더 작음
        double cMuNone = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0).cMu();
        double cMuSmoker = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 0.5).cMu();
        assertThat(cMuSmoker).isLessThan(cMuNone);
    }

    // ---- 골든값: 서버 구현과 다른 형태로 독립 산출한 값 ----
    // C(μ) 는 2^(-Δt/μ) 로, C'(μ) 는 중앙 차분(h=1e-5)으로, posterior 는 칼만(innovation) 형태
    // μ = μ₀ + K·(SOL − (α + β·C(μ₀))), K = σ₀²·ã / (ã²·σ₀² + σ²) 로 계산했다.
    // 수치 미분 오차가 ~1e-9 수준이라 허용 오차는 1e-6.
    private static final double GOLDEN_TOLERANCE = 1e-6;

    @Test
    void derivativeAtBedtime_matchesNumericDerivative() {
        // 200mg, 취침 4h 전, μ=5 → C'(μ) = 12.7394724167
        assertThat(BayesianHalfLifeUpdater.derivativeAtBedtime(List.of(dose(200, 4)), BEDTIME, PRIOR_MEAN, 1.0))
                .isCloseTo(12.739472416711804, within(GOLDEN_TOLERANCE));
    }

    @Test
    void derivativeAtBedtime_multipleDosesWithMultiplier_matchesNumericDerivative() {
        // 100mg(6h 전) + 150mg(2.5h 전), μ_base=5, 배수 1.2 → C'(μ_base) = 13.4223925926
        assertThat(BayesianHalfLifeUpdater.derivativeAtBedtime(
                List.of(dose(100, 6), dose(150, 2.5)), BEDTIME, PRIOR_MEAN, 1.2))
                .isCloseTo(13.422392592588038, within(GOLDEN_TOLERANCE));
    }

    @Test
    void derivativeAtBedtime_nonPositiveInputs_zero() {
        List<Dose> doses = List.of(dose(200, 4));
        assertThat(BayesianHalfLifeUpdater.derivativeAtBedtime(doses, BEDTIME, PRIOR_MEAN, 0)).isZero();
        assertThat(BayesianHalfLifeUpdater.derivativeAtBedtime(doses, BEDTIME, 0, 1.0)).isZero();
    }

    @Test
    void derivativeAtBedtime_ignoresDosesAfterBedtime() {
        Dose afterBedtime = new Dose(BEDTIME.plusSeconds(3600), 200);
        assertThat(BayesianHalfLifeUpdater.derivativeAtBedtime(List.of(afterBedtime), BEDTIME, PRIOR_MEAN, 1.0))
                .isZero();
    }

    @Test
    void linearize_matchesGolden() {
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        assertThat(linearization.cMu()).isCloseTo(114.86983549970348, within(1e-9)); // 200·2^(-4/5)
        assertThat(linearization.cPrime()).isCloseTo(12.739472416711804, within(GOLDEN_TOLERANCE));
    }

    @Test
    void posteriorEstimate_matchesGolden() {
        // 예측 SOL = 15 + 0.1·114.87 = 26.49분, 관측 30분 → 반감기 약간 증가
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 30);
        assertThat(raw.mean()).isCloseTo(5.097148937745553, within(GOLDEN_TOLERANCE));
        assertThat(raw.variance()).isCloseTo(2.170733107338518, within(GOLDEN_TOLERANCE));
    }

    @Test
    void posteriorEstimate_multipleDosesWithMultiplier_matchesGolden() {
        Linearization linearization = BayesianHalfLifeUpdater.linearize(
                BEDTIME, List.of(dose(100, 6), dose(150, 2.5)), PRIOR_MEAN, 1.2);
        Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 40);
        assertThat(linearization.cMu()).isCloseTo(162.3730307657511, within(1e-9));
        assertThat(raw.mean()).isCloseTo(5.2543273567430635, within(GOLDEN_TOLERANCE));
        assertThat(raw.variance()).isCloseTo(2.162346806777504, within(GOLDEN_TOLERANCE));
    }

    @Test
    void observedSolLongerThanPredicted_increasesHalfLife() {
        // 잠들기까지 예측(26.49분)보다 오래 걸림 → 취침 잔량이 더 많다 → 대사가 느리다(반감기 ↑)
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 60);
        assertThat(raw.mean()).isGreaterThan(PRIOR_MEAN)
                .isCloseTo(5.9267687740950175, within(GOLDEN_TOLERANCE));
    }

    @Test
    void observedSolShorterThanPredicted_decreasesHalfLife() {
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, 10);
        assertThat(raw.mean()).isLessThan(PRIOR_MEAN)
                .isCloseTo(4.54406904684591, within(GOLDEN_TOLERANCE));
    }

    @Test
    void observedSolEqualToPredicted_keepsMean() {
        Linearization linearization = BayesianHalfLifeUpdater.linearize(BEDTIME, List.of(dose(200, 4)), PRIOR_MEAN, 1.0);
        double predicted = BayesianHalfLifeUpdater.ALPHA + BayesianHalfLifeUpdater.BETA * linearization.cMu();
        Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(PRIOR_MEAN, PRIOR_VAR, linearization, predicted);
        assertThat(raw.mean()).isCloseTo(PRIOR_MEAN, within(1e-9));
    }

    @Test
    void safetyBounds_withinTrustRegion_passesThrough() {
        Posterior bounded = BayesianHalfLifeUpdater.applySafetyBounds(5.3, 0.5, PRIOR_MEAN);
        assertThat(bounded.mean()).isEqualTo(5.3);
        assertThat(bounded.variance()).isEqualTo(0.5);
    }

    @Test
    void safetyBounds_trustRegion_exactStep() {
        // 위·아래 모두 정확히 prior ± 0.5 로 잘린다 (abs 비교만으로는 방향 오류를 못 잡음)
        assertThat(BayesianHalfLifeUpdater.applySafetyBounds(ABSURD_MEAN, 0.5, PRIOR_MEAN).mean()).isEqualTo(5.5);
        assertThat(BayesianHalfLifeUpdater.applySafetyBounds(-ABSURD_MEAN, 0.5, PRIOR_MEAN).mean()).isEqualTo(4.5);
    }
}
