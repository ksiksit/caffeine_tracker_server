package com.jongbeom.server.domain.settings;

/**
 * 다른 도메인이 읽는 설정 값 묶음. 영속 엔티티({@code UserSettings})를 도메인 밖으로 내보내지 않기 위한 읽기 전용 사본이라,
 * 값을 바꾸려면 {@code UserSettingsService}의 메서드를 거쳐야 한다.
 *
 * @param effectiveHalfLifeHours 실제 계산에 쓰는 반감기(학습값·건강상태 배수 반영)
 * @param conditionMultiplier    건강상태 배수({@code CaffeineCondition})
 * @param learnedMean            학습 prior 평균(base, 배수 미적용)
 */
public record SettingsSnapshot(
        double effectiveHalfLifeHours,
        double conditionMultiplier,
        int bedtimeHour,
        int bedtimeMinute,
        int referenceDoseMg,
        boolean learningEnabled,
        double learnedMean,
        double learnedVariance,
        boolean notifyCutoff,
        boolean notifyBedtimeResidual,
        boolean notifyRecordReminder,
        boolean notifyCafeNearby
) {
}
