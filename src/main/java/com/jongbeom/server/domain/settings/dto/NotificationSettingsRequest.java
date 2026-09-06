package com.jongbeom.server.domain.settings.dto;

import jakarta.validation.constraints.NotNull;

/** 알림 종류별 on/off. 설정 전체 교체의 일부라 세 필드 모두 필수. */
public record NotificationSettingsRequest(
        @NotNull Boolean cutoff,
        @NotNull Boolean bedtimeResidual,
        @NotNull Boolean recordReminder
) {
}
