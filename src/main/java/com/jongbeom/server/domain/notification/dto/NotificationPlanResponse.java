package com.jongbeom.server.domain.notification.dto;

import java.time.Instant;
import java.util.List;

/**
 * 앱이 예약할 로컬 알림 계획. {@code items} 는 {@code now} 이후·설정에서 켜진 종류만, fireAt 오름차순.
 * 앱은 기존 예약을 전부 취소하고 이 목록대로 다시 예약한다.
 */
public record NotificationPlanResponse(
        Instant now,
        List<NotificationItem> items
) {
    /** {@code type} 은 {@link com.jongbeom.server.domain.notification.NotificationType} 이름. 와이어는 String(CutoffResponse.status 관례). */
    public record NotificationItem(
            String type,
            Instant fireAt,
            String title,
            String body
    ) {
    }
}
