package com.jongbeom.server.learning;

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

    @Column(name = "predicted_residual", nullable = false)
    private double predictedResidualAtBedtime;

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

    /** 검증 통과 시에만 생성(← Swift init 레벨 검증). 무효값이면 IllegalArgumentException. */
    public static HalfLifeObservation create(
            Long userId, LocalDate date, double predictedResidualAtBedtime, double observedSolMinutes,
            double priorMean, double priorVariance, double posteriorMean, double posteriorVariance,
            double conditionMultiplier) {
        validate("predictedResidualAtBedtime", predictedResidualAtBedtime);
        validate("observedSolMinutes", observedSolMinutes);
        validate("priorMean", priorMean);
        validate("priorVariance", priorVariance);
        validate("posteriorMean", posteriorMean);
        validate("posteriorVariance", posteriorVariance);
        validate("conditionMultiplier", conditionMultiplier);
        require(predictedResidualAtBedtime >= 0, "predictedResidualAtBedtime >= 0");
        require(observedSolMinutes >= 0, "observedSolMinutes >= 0");
        require(priorMean > 0, "priorMean > 0");
        require(posteriorMean > 0, "posteriorMean > 0");
        require(priorVariance > 0, "priorVariance > 0");
        require(posteriorVariance > 0, "posteriorVariance > 0");
        require(conditionMultiplier > 0, "conditionMultiplier > 0");
        return new HalfLifeObservation(userId, date, predictedResidualAtBedtime, observedSolMinutes,
                priorMean, priorVariance, posteriorMean, posteriorVariance, conditionMultiplier);
    }

    private static void validate(String field, double value) {
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
