package com.jongbeom.server.settings.dto;

import com.jongbeom.server.settings.UserSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "유저 설정 + 학습 상태")
public record SettingsResponse(
        @Schema(description = "슬라이더 반감기(h, base)", example = "5.0") double halfLife,
        @Schema(description = "건강 상태 rawValue (0=없음,1=흡연,2=경구피임약,3=임신)", example = "0") int condition,
        @Schema(description = "취침 시(0~23)", example = "23") int bedtimeHour,
        @Schema(description = "취침 분(0~59)", example = "0") int bedtimeMinute,
        @Schema(description = "마감시각 계산 기준 용량(mg)", example = "75") int referenceDoseMg,
        @Schema(description = "자동 학습 ON/OFF", example = "true") boolean isLearningEnabled,
        @Schema(description = "학습된 사후 평균(h, base)", example = "5.0") double learnedMean,
        @Schema(description = "학습된 사후 분산(h²)", example = "2.25") double learnedVariance,
        @Schema(description = "마지막 학습 날짜(없으면 null)", example = "2026-05-30") LocalDate lastLearnedDate,
        @Schema(description = "[파생] 학습/슬라이더로 결정된 base 반감기(h)", example = "5.0") double inferredBaseHalfLife,
        @Schema(description = "[파생] condition 적용된 실제 반감기(h)", example = "5.0") double effectiveHalfLifeHours
) {
    public static SettingsResponse from(UserSettings s) {
        return new SettingsResponse(
                s.getHalfLife(), s.getCondition(), s.getBedtimeHour(), s.getBedtimeMinute(),
                s.getReferenceDoseMg(), s.isLearningEnabled(), s.getLearnedMean(), s.getLearnedVariance(),
                s.getLastLearnedDate(), s.inferredBaseHalfLife(), s.effectiveHalfLifeHours());
    }
}
