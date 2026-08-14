package com.jongbeom.server.learning.entity;

import com.jongbeom.server.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 베이지안 학습 1회 관측 이력. iOS SwiftData {@code HalfLifeObservation} 이관.
 * date 는 "그 밤이 끝나는 아침의 로컬 날짜"(LocalDate) = 중복 학습 방지 키.
 */
@Entity
@Getter
@Table(name = "half_life_observations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HalfLifeObservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "obs_date", nullable = false)
    private LocalDate date;

    /** 취침 시 예측 잔량(mg). 컬럼명은 축약형 predicted_residual — DB 변경 금지라 이름 차이 유지. */
    @Column(name = "predicted_residual", nullable = false)
    private double predictedResidualAtBedtime;

    /** SOL(sleep onset latency, 수면 잠복기) 관측값(분). */
    @Column(name = "observed_sol_minutes", nullable = false)
    private double observedSolMinutes;

    @Column(name = "prior_mean", nullable = false)
    private double priorMean;

    @Column(name = "prior_variance", nullable = false)
    private double priorVariance;

    @Column(name = "posterior_mean", nullable = false)
    private double posteriorMean;

    @Column(name = "posterior_variance", nullable = false)
    private double posteriorVariance;

    @Column(name = "condition_multiplier", nullable = false)
    private double conditionMultiplier;

    private HalfLifeObservation(Long userId, LocalDate date, double predictedResidualAtBedtime,
                                double observedSolMinutes, double priorMean, double priorVariance,
                                double posteriorMean, double posteriorVariance, double conditionMultiplier) {
        this.userId = userId;
        this.date = date;
        this.predictedResidualAtBedtime = predictedResidualAtBedtime;
        this.observedSolMinutes = observedSolMinutes;
        this.priorMean = priorMean;
        this.priorVariance = priorVariance;
        this.posteriorMean = posteriorMean;
        this.posteriorVariance = posteriorVariance;
        this.conditionMultiplier = conditionMultiplier;
    }

    /** 학습 prior 상태 (mean: 시간, variance: 시간²). */
    public record Prior(double mean, double variance) {
    }

    /** 학습 posterior 상태 (mean: 시간, variance: 시간²). {@code calc}의 Posterior와 별개 타입(엔티티 결합 방지). */
    public record Posterior(double mean, double variance) {
    }

    /**
     * 검증 통과 시에만 생성(← Swift init 레벨 검증). 무효값이면 IllegalArgumentException.
     * prior/posterior를 타입으로 묶어 연속 double 인자의 자리 바꿈 실수를 막는다.
     */
    public static HalfLifeObservation create(
            Long userId, LocalDate date, double predictedResidualAtBedtime, double observedSolMinutes,
            Prior prior, Posterior posterior, double conditionMultiplier) {
        // 검증 순서는 기존과 동일: 유한성 전체 → 범위 전체
        requireFinite("predictedResidualAtBedtime", predictedResidualAtBedtime);
        requireFinite("observedSolMinutes", observedSolMinutes);
        requireFinite("priorMean", prior.mean());
        requireFinite("priorVariance", prior.variance());
        requireFinite("posteriorMean", posterior.mean());
        requireFinite("posteriorVariance", posterior.variance());
        requireFinite("conditionMultiplier", conditionMultiplier);
        require(predictedResidualAtBedtime >= 0, "predictedResidualAtBedtime >= 0");
        require(observedSolMinutes >= 0, "observedSolMinutes >= 0");
        require(prior.mean() > 0, "priorMean > 0");
        require(posterior.mean() > 0, "posteriorMean > 0");
        require(prior.variance() > 0, "priorVariance > 0");
        require(posterior.variance() > 0, "posteriorVariance > 0");
        require(conditionMultiplier > 0, "conditionMultiplier > 0");
        return new HalfLifeObservation(userId, date, predictedResidualAtBedtime, observedSolMinutes,
                prior.mean(), prior.variance(), posterior.mean(), posterior.variance(), conditionMultiplier);
    }

    private static void requireFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("비유한 값: " + field + "=" + value);
        }
    }

    private static void require(boolean condition, String rule) {
        if (!condition) {
            throw new IllegalArgumentException("관측값 제약 위반: " + rule);
        }
    }
}
