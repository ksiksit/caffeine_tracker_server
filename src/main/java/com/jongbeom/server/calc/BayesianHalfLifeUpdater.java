package com.jongbeom.server.calc;

import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import java.time.Instant;
import java.util.List;

/**
 * 수면 잠복기(SOL) 관측으로 base 반감기를 정규-정규 켤레(선형화) 모델로 베이지안 갱신.
 * iOS {@code BayesianHalfLifeLearner}의 순수 계산부 포팅. 상수·공식 그대로.
 *
 * <p>학술 근거: Kovar 2021, Ramakrishnan 2016, Koletzko 2021, Drake 2013.
 */
public final class BayesianHalfLifeUpdater {

    private BayesianHalfLifeUpdater() {
    }

    // likelihood 파라미터 (모집단 평균값). LearningStatsCalc와 동기화 필수.
    public static final double ALPHA = 15.0;       // 0mg일 때 평균 SOL(분)
    public static final double BETA = 0.10;        // mg당 SOL 증가(분/mg)
    public static final double SIGMA_OBS = 10.0;   // SOL 관측 노이즈 표준편차(분)

    public static final double CLAMP_MIN = Pharmacokinetics.MIN_HALF_LIFE_HOURS; // 3
    public static final double CLAMP_MAX = Pharmacokinetics.MAX_HALF_LIFE_HOURS; // 7
    public static final double MAX_STEP_PER_UPDATE = 0.5;       // trust region(h)
    public static final double VARIANCE_FLOOR = 0.05 * 0.05;    // 0.0025
    public static final double DOSES_LOOKBACK_HOURS = 24;
    public static final double MIN_RESIDUAL_FOR_LEARNING = 1.0; // mg
    public static final double MAX_ACCEPTABLE_SOL_MINUTES = 180;
    public static final long MIN_SLEEP_DURATION_SECONDS = 4 * 3600;

    /** prior 평균 주변 선형화 결과. cMu = 취침 시 잔량, cPrime = ∂C/∂μ_base. */
    public record Linearization(double cMu, double cPrime) {
    }

    public record Posterior(double mean, double variance) {
    }

    /**
     * prior 평균에서 C(μ), C'(μ) 계산. 잔량 C(μ) &lt; minResidual이면 정보량 부족으로 null.
     */
    public static Linearization linearize(
            Instant bedtime, List<Dose> doses, double priorMean, double multiplier) {
        double effectiveMu = priorMean * multiplier;
        double cMu = Pharmacokinetics.totalRemaining(bedtime, doses, effectiveMu);
        if (cMu < MIN_RESIDUAL_FOR_LEARNING) {
            return null;
        }
        double cPrime = derivativeAtBedtime(doses, bedtime, priorMean, multiplier);
        return new Linearization(cMu, cPrime);
    }

    /**
     * ∂C/∂μ_base. C = Σⱼ doseⱼ·exp(-ln2/(μ_base·m)·Δtⱼ),
     * dC/dμ_base = Σⱼ doseⱼ·exp(...)·(ln2·Δtⱼ)/(m·μ_base²).
     */
    public static double derivativeAtBedtime(
            List<Dose> doses, Instant bedtime, double baseMu, double multiplier) {
        double effectiveMu = baseMu * multiplier;
        if (effectiveMu <= 0 || baseMu <= 0 || multiplier <= 0) {
            return 0;
        }
        double ke = Math.log(2) / effectiveMu;
        double sum = 0;
        for (Dose dose : doses) {
            double dtHours = (bedtime.toEpochMilli() - dose.timestamp().toEpochMilli()) / 3_600_000.0;
            if (dtHours < 0) {
                continue;
            }
            double exponent = Math.exp(-ke * dtHours);
            sum += dose.amountMg() * exponent * (Math.log(2) * dtHours) / (multiplier * baseMu * baseMu);
        }
        return sum;
    }

    /** 정규-정규 켤레 갱신. trust region/clamp 이전의 raw posterior. */
    public static Posterior posteriorEstimate(
            double priorMean, double priorVariance, Linearization lin, double solMinutes) {
        double aTilde = BETA * lin.cPrime();
        double yTilde = solMinutes - ALPHA - BETA * (lin.cMu() - lin.cPrime() * priorMean);
        double priorPrec = 1.0 / priorVariance;
        double obsPrec = (aTilde * aTilde) / (SIGMA_OBS * SIGMA_OBS);
        double postPrec = priorPrec + obsPrec;
        double rawPostVar = 1.0 / postPrec;
        double rawPostMean = rawPostVar * (priorMean / priorVariance
                + (aTilde * yTilde) / (SIGMA_OBS * SIGMA_OBS));
        return new Posterior(rawPostMean, rawPostVar);
    }

    /** trust region(±0.5h) → clamp[3,7] → variance floor(0.05²). */
    public static Posterior applySafetyBounds(double rawMean, double rawVariance, double priorMean) {
        double stepLimited = Math.max(
                Math.min(rawMean, priorMean + MAX_STEP_PER_UPDATE),
                priorMean - MAX_STEP_PER_UPDATE);
        double clamped = Math.max(Math.min(stepLimited, CLAMP_MAX), CLAMP_MIN);
        double flooredVar = Math.max(rawVariance, VARIANCE_FLOOR);
        return new Posterior(clamped, flooredVar);
    }
}
