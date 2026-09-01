package com.jongbeom.server.domain.settings.dto;

import com.jongbeom.server.domain.settings.entity.UserSettings;
import java.time.LocalDate;

public record SettingsResponse(
        double halfLife,
        int condition,
        int bedtimeHour,
        int bedtimeMinute,
        int referenceDoseMg,
        boolean isLearningEnabled,
        double learnedMean,
        double learnedVariance,
        LocalDate lastLearnedDate,
        double inferredBaseHalfLife,
        double effectiveHalfLifeHours
) {
    public static SettingsResponse from(UserSettings s) {
        return new SettingsResponse(
                s.getHalfLife(), s.getCondition(), s.getBedtimeHour(), s.getBedtimeMinute(),
                s.getReferenceDoseMg(), s.isLearningEnabled(), s.getLearnedMean(), s.getLearnedVariance(),
                s.getLastLearnedDate(), s.inferredBaseHalfLife(), s.effectiveHalfLifeHours());
    }
}
