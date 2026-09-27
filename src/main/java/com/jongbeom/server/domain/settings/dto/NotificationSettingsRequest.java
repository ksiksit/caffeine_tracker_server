package com.jongbeom.server.domain.settings.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 알림 종류별 on/off. 설정 전체 교체의 일부라 기존 세 필드는 필수.
 * {@code cafeNearby} 는 선택 — 생략하면 기존 값을 유지한다(이 필드를 모르는 이전 버전 앱 호환).
 */
public record NotificationSettingsRequest(
        @NotNull Boolean cutoff,
        @NotNull Boolean bedtimeResidual,
        @NotNull Boolean recordReminder,
        Boolean cafeNearby
) {
}
