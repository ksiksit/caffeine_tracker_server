package com.jongbeom.server.domain.notification.dto;

import java.time.Instant;
import java.util.List;

/**
 * 앱이 예약할 로컬 알림 계획. {@code items} 는 {@code now} 이후·설정에서 켜진 종류만, fireAt 오름차순.
 * 앱은 기존 예약을 전부 취소하고 이 목록대로 다시 예약한다.
 * {@code cafeNearbyWindows} 는 "이 구간에 카페 구역에 들어서면 울려라"는 조건이다 — 서버는 언제만 알고,
 * 어디(카페 좌표·사용자 위치)는 모른다. 판정은 앱이 지오펜스 진입 순간에 한다.
 */
public record NotificationPlanResponse(
        Instant now,
        List<NotificationItem> items,
        List<CafeNearbyWindow> cafeNearbyWindows
) {
    /** {@code type} 은 {@link com.jongbeom.server.domain.notification.NotificationType} 이름. 와이어는 String(CutoffResponse.status 관례). */
    public record NotificationItem(
            String type,
            Instant fireAt,
            String title,
            String body
    ) {
    }

    /**
     * 카페 근처 알림 금지 구간 [activeFrom, activeUntil). {@code reason} 은
     * {@link com.jongbeom.server.domain.notification.CafeNearbyReason} 이름(와이어는 String — NotificationItem.type 관례).
     */
    public record CafeNearbyWindow(
            String reason,
            Instant activeFrom,
            Instant activeUntil,
            String title,
            String body
    ) {
    }
}
