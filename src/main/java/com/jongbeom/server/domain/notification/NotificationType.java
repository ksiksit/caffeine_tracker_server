package com.jongbeom.server.domain.notification;

/**
 * 시각에 울리는 로컬 알림 종류. 응답 {@code NotificationItem.type} 의 값이며 설정 {@code notifications.*} 토글과 1:1.
 * 종류별 생성 조건·문구는 {@code NotificationPlanService}(코드)와 docs/api/api.md 알림 절(계약)에 있다.
 * 카페 근처 알림({@code notifications.cafeNearby})은 시각이 아니라 구간이라 여기 없다 — {@link CafeNearbyReason} 참조.
 */
public enum NotificationType {
    /** 섭취 마감 알림 — 섭취 마감시각 30분 전. */
    CUTOFF,
    /** 취침 잔량 예고 — 취침시각 60분 전, 취침 시 예상 잔량 50mg 이상일 때. */
    BEDTIME_RESIDUAL,
    /** 기록 리마인더 — 오늘 기록이 없고 평소 첫 기록 시각(최근 14일 일별 중앙값) + 2시간이 지나기 전. */
    RECORD_REMINDER
}
