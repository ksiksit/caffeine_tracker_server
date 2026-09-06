package com.jongbeom.server.domain.notification;

/**
 * 로컬 알림 종류. 응답 {@code NotificationItem.type} 의 값이며 설정 {@code notifications.*} 토글과 1:1.
 * 종류별 생성 조건·문구는 각 종류를 구현할 때 {@code NotificationPlanService} 에 추가한다.
 */
public enum NotificationType {
    /** 섭취 마감 알림 — 섭취 마감시각 전. */
    CUTOFF,
    /** 취침 잔량 예고 — 취침시각 전. */
    BEDTIME_RESIDUAL,
    /** 기록 리마인더 — 평소 기록 패턴에서 벗어나 기록이 없을 때. */
    RECORD_REMINDER
}
