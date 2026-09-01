package com.jongbeom.server.domain.settings.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 설정 갱신(전체 교체). learnedMean/Variance/lastLearnedDate 는 선택 — 보내면 학습 상태를 1회 시드한다
 * (기존 기기의 누적 학습값 이전용). 미전송 시 서버가 관리하는 학습 상태를 보존한다.
 */
public record UpdateSettingsRequest(
        @NotNull @DecimalMin("3.0") @DecimalMax("7.0") Double halfLife,
        @NotNull @Min(0) @Max(3) Integer condition,
        @NotNull @Min(0) @Max(23) Integer bedtimeHour,
        @NotNull @Min(0) @Max(59) Integer bedtimeMinute,
        @NotNull @Min(1) @Max(1000) Integer referenceDoseMg,
        @NotNull Boolean isLearningEnabled,
        Double learnedMean,
        Double learnedVariance,
        LocalDate lastLearnedDate
) {
    public boolean hasLearnedSeed() {
        return learnedMean != null && learnedVariance != null;
    }
}
