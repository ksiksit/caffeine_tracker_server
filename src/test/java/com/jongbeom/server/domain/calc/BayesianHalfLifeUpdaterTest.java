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
}
