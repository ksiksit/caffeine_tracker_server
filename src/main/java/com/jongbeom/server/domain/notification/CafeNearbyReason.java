package com.jongbeom.server.domain.notification;

/**
 * 카페 근처 알림 금지 구간이 생긴 이유. 응답 {@code CafeNearbyWindow.reason} 의 값.
 * 하루에 구간은 하나이고, 여러 이유가 겹치면 위에 있는 것이 이긴다(초과 &gt; 하루 권장량 &gt; 마감 지남).
 * 생성 조건·문구는 {@code NotificationPlanService}(코드)와 docs/api/api.md 알림 절(계약)에 있다.
 */
public enum CafeNearbyReason {
    /** 취침 시 예상 잔량이 이미 50mg 이상(오늘 현황 cutoff.status = ALREADY_EXCEEDED) — 지금부터. */
    ALREADY_EXCEEDED,
    /** 오늘 섭취량이 하루 권장량(400mg) 초과 — 지금부터. */
    DAILY_LIMIT_EXCEEDED,
    /** 섭취 마감 시각이 지남(cutoff.status = CUTOFF) — 마감 시각부터. */
    CUTOFF_PASSED
}
