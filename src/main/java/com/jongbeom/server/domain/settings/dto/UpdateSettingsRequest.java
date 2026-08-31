package com.jongbeom.server.domain.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "설정 갱신 요청")
public record UpdateSettingsRequest(
        @Schema(description = "슬라이더 반감기(h). 변경 시 학습 prior 리셋", example = "5.0")
        @NotNull @DecimalMin("3.0") @DecimalMax("7.0") Double halfLife,

        @Schema(description = "건강 상태 rawValue(0~3)", example = "0")
        @NotNull @Min(0) @Max(3) Integer condition,

        @Schema(description = "취침 시(0~23)", example = "23")
        @NotNull @Min(0) @Max(23) Integer bedtimeHour,

        @Schema(description = "취침 분(0~59)", example = "0")
        @NotNull @Min(0) @Max(59) Integer bedtimeMinute,

        @Schema(description = "마감시각 기준 용량(mg)", example = "75")
        @NotNull @Min(1) @Max(1000) Integer referenceDoseMg,

        @Schema(description = "자동 학습 ON/OFF", example = "true")
        @NotNull Boolean isLearningEnabled,

        @Schema(description = "[선택] 학습값 시드 — 사후 평균(h)", example = "5.2")
        Double learnedMean,

        @Schema(description = "[선택] 학습값 시드 — 사후 분산(h²)", example = "1.8")
        Double learnedVariance,

        @Schema(description = "[선택] 학습값 시드 — 마지막 학습 날짜")
        LocalDate lastLearnedDate
) {
    public boolean hasLearnedSeed() {
        return learnedMean != null && learnedVariance != null;
    }
}
