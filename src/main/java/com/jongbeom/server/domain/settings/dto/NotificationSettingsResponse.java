package com.jongbeom.server.domain.settings.dto;

import com.jongbeom.server.domain.settings.entity.UserSettings;

/** 알림 종류별 on/off. 알림 계획 조회에서 꺼진 종류는 생략된다. */
public record NotificationSettingsResponse(
        boolean cutoff,
        boolean bedtimeResidual,
        boolean recordReminder
) {
    public static NotificationSettingsResponse from(UserSettings s) {
        return new NotificationSettingsResponse(
                s.isNotifyCutoff(), s.isNotifyBedtimeResidual(), s.isNotifyRecordReminder());
    }
}
