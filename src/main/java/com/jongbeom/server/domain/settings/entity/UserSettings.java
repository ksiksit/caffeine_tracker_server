package com.jongbeom.server.domain.settings.entity;

import com.jongbeom.server.domain.calc.Pharmacokinetics;
import com.jongbeom.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저별 설정 + 베이지안 학습 상태(server-authoritative). iOS {@code SettingsManager} 의 @AppStorage 이관.
 * (기기 종속 키 {@code hasRequestedHealthKitAuthorization} 는 제외 — 서버 비저장)
 *
 * <p><b>반감기 관련 개념 4개의 관계</b> — 헷갈리기 쉬우므로 주의:
 * <pre>
 * halfLife(슬라이더 수동값)     learnedMean(베이지안 학습값)
 *        └── 학습 OFF ──┐    ┌── 학습 ON ──┘
 *              inferredBaseHalfLife()  ×  condition multiplier  =  effectiveHalfLifeHours()
 * </pre>
 * 잔량·마감시각 등 실제 연산은 항상 {@link #effectiveHalfLifeHours()}를 사용한다.
 */
@Entity
@Getter
@Table(name = "user_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSettings extends BaseTimeEntity {

    /** Koletzko 2021 모집단 분산(1.5²). prior 리셋 시 사용. (← SettingsManager.populationPriorVariance) */
    public static final double POPULATION_PRIOR_VARIANCE = 1.5 * 1.5;

    // 신규 유저 기본값 (← SettingsManager.init)
    private static final int DEFAULT_BEDTIME_HOUR = 23;
    private static final int DEFAULT_BEDTIME_MINUTE = 0;
    private static final int DEFAULT_REFERENCE_DOSE_MG = 75;

    /** users.id 와 동일(공유 PK). */
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "half_life", nullable = false)
    private double halfLife;

    /** {@link CaffeineCondition} rawValue (0~3). 컬럼명 health_condition·API 필드명 condition — DB/와이어 동결로 이름 차이 유지. */
    @Column(name = "health_condition", nullable = false)
    private int condition;

    @Column(name = "bedtime_hour", nullable = false)
    private int bedtimeHour;

    @Column(name = "bedtime_minute", nullable = false)
    private int bedtimeMinute;

    @Column(name = "reference_dose_mg", nullable = false)
    private int referenceDoseMg;

    @Column(name = "is_learning_enabled", nullable = false)
    private boolean learningEnabled;

    @Column(name = "learned_mean", nullable = false)
    private double learnedMean;

    @Column(name = "learned_variance", nullable = false)
    private double learnedVariance;

    @Column(name = "last_learned_date")
    private LocalDate lastLearnedDate;

    private UserSettings(Long userId) {
        this.userId = userId;
        this.halfLife = Pharmacokinetics.DEFAULT_HALF_LIFE_HOURS;
        this.condition = CaffeineCondition.NONE.rawValue();
        this.bedtimeHour = DEFAULT_BEDTIME_HOUR;
        this.bedtimeMinute = DEFAULT_BEDTIME_MINUTE;
        this.referenceDoseMg = DEFAULT_REFERENCE_DOSE_MG;
        this.learningEnabled = true;
        this.learnedMean = Pharmacokinetics.DEFAULT_HALF_LIFE_HOURS;
        this.learnedVariance = POPULATION_PRIOR_VARIANCE;
        this.lastLearnedDate = null;
    }

    /** 신규 유저 기본 설정(← SettingsManager.init 기본값). */
    public static UserSettings createDefault(Long userId) {
        return new UserSettings(userId);
    }

    /**
     * 편집 가능한 필드 일괄 갱신. halfLife 가 바뀌면 prior 를 리셋한다.
     * (← SettingsManager.halfLife didSet: learnedMean=halfLife, variance=모집단, lastLearnedDate=nil)
     */
    public void update(double halfLife, int condition, int bedtimeHour, int bedtimeMinute,
                       int referenceDoseMg, boolean learningEnabled) {
        // 의도적 정확 비교(!=) — 슬라이더 값이 문자 그대로 바뀐 경우에만 prior 리셋 (iOS didSet 동일)
        if (this.halfLife != halfLife) {
            this.learnedMean = halfLife;
            this.learnedVariance = POPULATION_PRIOR_VARIANCE;
            this.lastLearnedDate = null;
        }
        this.halfLife = halfLife;
        this.condition = condition;
        this.bedtimeHour = bedtimeHour;
        this.bedtimeMinute = bedtimeMinute;
        this.referenceDoseMg = referenceDoseMg;
        this.learningEnabled = learningEnabled;
    }

    /** 학습값 1회 시드(기존 기기의 누적 학습 상태 이전용). 동작은 {@link #applyLearning}과 동일 — 호출 의도만 다르다. */
    public void seedLearned(double learnedMean, double learnedVariance, LocalDate lastLearnedDate) {
        applyLearning(learnedMean, learnedVariance, lastLearnedDate);
    }

    /** 베이지안 학습 1회 결과 반영(관측 저장 성공 후 호출). */
    public void applyLearning(double learnedMean, double learnedVariance, LocalDate lastLearnedDate) {
        this.learnedMean = learnedMean;
        this.learnedVariance = learnedVariance;
        this.lastLearnedDate = lastLearnedDate;
    }

    /** 학습 ON 이면 사후 평균, 아니면 슬라이더 값 (둘 다 base, condition 미적용). */
    public double inferredBaseHalfLife() {
        return learningEnabled ? learnedMean : halfLife;
    }

    /** 실제 계산에 쓰는 반감기 = base × condition multiplier. */
    public double effectiveHalfLifeHours() {
        return inferredBaseHalfLife() * CaffeineCondition.fromRawValue(condition).multiplier();
    }
}
