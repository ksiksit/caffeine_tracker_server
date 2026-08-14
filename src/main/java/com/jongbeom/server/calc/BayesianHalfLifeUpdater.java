package com.jongbeom.server.calc;

import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import java.time.Instant;
import java.util.List;

/**
 * 수면 잠복기(SOL) 관측으로 base 반감기를 정규-정규 켤레(선형화) 모델로 베이지안 갱신.
 * iOS {@code BayesianHalfLifeLearner}의 순수 계산부 포팅. 상수·공식 그대로.
 *
 * <p>모델: SOL ≈ α + β·C(μ) + ε, ε ~ N(0, σ²). C(μ)를 prior 평균 μ₀ 주변에서 선형화해
 * SOL ≈ ã·μ + const 꼴로 만들고(ã = β·C'), 정규-정규 켤레 공식으로 posterior를 닫힌형으로 얻는다.
 *
 * <p>학술 근거: Kovar 2021, Ramakrishnan 2016, Koletzko 2021, Drake 2013.
 */
public final class BayesianHalfLifeUpdater {

    private BayesianHalfLifeUpdater() {
    }

    // likelihood 파라미터 (모집단 평균값). LearningStatsCalc와 동기화 필수.
    public static final double ALPHA = 15.0;       // 0mg일 때 평균 SOL(분) — 0.15로 "정규화" 금지
    public static final double BETA = 0.10;        // mg당 SOL 증가(분/mg)
    public static final double SIGMA_OBS = 10.0;   // SOL 관측 노이즈 표준편차(분)

    /** 반감기 clamp 범위(시간). */
    public static final double HALF_LIFE_CLAMP_MIN_HOURS = Pharmacokinetics.MIN_HALF_LIFE_HOURS; // 3
    public static final double HALF_LIFE_CLAMP_MAX_HOURS = Pharmacokinetics.MAX_HALF_LIFE_HOURS; // 7
    public static final double MAX_STEP_PER_UPDATE = 0.5;       // trust region(h)
    public static final double VARIANCE_FLOOR = 0.05 * 0.05;    // 0.0025
    public static final double DOSES_LOOKBACK_HOURS = 24;
    public static final double MIN_RESIDUAL_FOR_LEARNING = 1.0; // mg
    public static final double MAX_ACCEPTABLE_SOL_MINUTES = 180;
    public static final long MIN_SLEEP_DURATION_SECONDS = 4 * 3600;

    // LN_2는 리터럴로 바꾸지 말 것 — Math.log(2) 결과 그대로 써야 iOS 골든과 비트 일치.
    private static final double LN_2 = Math.log(2);
    private static final double MILLIS_PER_HOUR = 3_600_000.0;

    /** prior 평균 주변 선형화 결과. cMu = 취침 시 잔량(mg), cPrime = ∂C/∂μ_base (mg/시간). */
    public record Linearization(double cMu, double cPrime) {
    }

    /** posterior 분포 (mean: 시간, variance: 시간²). */
    public record Posterior(double mean, double variance) {
    }

    /**
     * prior 평균에서 C(μ), C'(μ) 계산. 잔량 C(μ) &lt; minResidual이면 정보량 부족으로 null.
     *
     * @param bedtime    취침 절대시각(UTC)
     * @param priorMean  prior 반감기 평균(시간)
     * @param multiplier {@code CaffeineCondition} 건강상태 배수(무차원)
     * @return 선형화 결과, 학습 불가 시 null
     */
    public static Linearization linearize(
            Instant bedtime, List<Dose> doses, double priorMean, double multiplier) {
        double effectiveMu = priorMean * multiplier; // 유효 반감기(시간) = base × 배수
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
     * (public은 단위 테스트 검증용 — 프로덕션 호출은 {@link #linearize}뿐)
     */
    public static double derivativeAtBedtime(
            List<Dose> doses, Instant bedtime, double baseMu, double multiplier) {
        double effectiveMu = baseMu * multiplier;
        if (effectiveMu <= 0 || baseMu <= 0 || multiplier <= 0) {
            return 0;
        }
        double ke = LN_2 / effectiveMu;
        double sum = 0;
        for (Dose dose : doses) {
            double dtHours = (bedtime.toEpochMilli() - dose.timestamp().toEpochMilli()) / MILLIS_PER_HOUR;
            if (dtHours < 0) {
                continue;
            }
            double exponent = Math.exp(-ke * dtHours);
            // 그룹핑 변경 금지 — 인수분해/약분하면 부동소수점 결과가 iOS와 어긋난다.
            double decayedAmount = dose.amountMg() * exponent;
            double sensitivityFactor = LN_2 * dtHours;
            double denominator = multiplier * baseMu * baseMu;
            sum += decayedAmount * sensitivityFactor / denominator;
        }
        return sum;
    }

    /**
     * 정규-정규 켤레 갱신. trust region/clamp 이전의 raw posterior.
     *
     * <p>선형화 모델 SOL ≈ α + β·(C(μ₀) + C'·(μ − μ₀)) 를 μ에 대해 정리하면 ỹ = ã·μ + ε 꼴이 되고
     * (ã = β·C', ỹ = SOL − α − β·(C(μ₀) − C'·μ₀)), 켤레 공식으로
     * posterior 정밀도 = prior 정밀도 + ã²/σ², posterior 평균 = postVar·(μ₀/priorVar + ã·ỹ/σ²).
     *
     * @param priorVariance 반드시 &gt; 0 (0이면 1/priorVariance → ∞)
     * @param solMinutes    관측 SOL(분)
     */
    public static Posterior posteriorEstimate(
            double priorMean, double priorVariance, Linearization linearization, double solMinutes) {
        double solSensitivityPerHour = BETA * linearization.cPrime(); // ã: 반감기 1h당 SOL 변화(분)
        double centeredSolMinutes = solMinutes - ALPHA
                - BETA * (linearization.cMu() - linearization.cPrime() * priorMean); // ỹ
        double priorPrecision = 1.0 / priorVariance;
        double obsPrecision = (solSensitivityPerHour * solSensitivityPerHour) / (SIGMA_OBS * SIGMA_OBS);
        double posteriorPrecision = priorPrecision + obsPrecision;
        double rawPostVar = 1.0 / posteriorPrecision;
        // 의도적으로 priorPrecision을 재사용하지 않음(priorMean/priorVariance ≠ priorMean*priorPrecision).
        // iOS와 부동소수점 결과 비트 일치를 위해 나눗셈 형태 유지.
        double rawPostMean = rawPostVar * (priorMean / priorVariance
                + (solSensitivityPerHour * centeredSolMinutes) / (SIGMA_OBS * SIGMA_OBS));
        return new Posterior(rawPostMean, rawPostVar);
    }

    /** trust region(±0.5h) → clamp[3,7] → variance floor(0.05²). */
    public static Posterior applySafetyBounds(double rawMean, double rawVariance, double priorMean) {
        double stepLimited = clamp(rawMean,
                priorMean - MAX_STEP_PER_UPDATE, priorMean + MAX_STEP_PER_UPDATE);
        double clamped = clamp(stepLimited, HALF_LIFE_CLAMP_MIN_HOURS, HALF_LIFE_CLAMP_MAX_HOURS);
        double flooredVar = Math.max(rawVariance, VARIANCE_FLOOR);
        return new Posterior(clamped, flooredVar);
    }

    /** [lower, upper] 구간으로 제한. 기존 중첩 min/max와 동일 식(NaN 전파 포함). */
    private static double clamp(double value, double lower, double upper) {
        return Math.max(Math.min(value, upper), lower);
    }
}
