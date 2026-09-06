package com.jongbeom.server.domain.notification.service;

import com.jongbeom.server.domain.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.domain.caffeine.service.CaffeineService;
import com.jongbeom.server.domain.calc.Pharmacokinetics;
import com.jongbeom.server.domain.calc.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.domain.notification.NotificationType;
import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse;
import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse.NotificationItem;
import com.jongbeom.server.domain.settings.entity.UserSettings;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 알림 계획 계산. 서버는 푸시를 보내지 않고(운영 EC2 외부 인터넷 불가) 계획만 내려준다 — CLAUDE.md 도메인 계약.
 * 종류별 생성 규칙의 계약은 docs/api.md 알림 절이며, 여기의 상수·문구를 바꾸면 그 표도 같이 바꾼다.
 * 구현된 종류: CUTOFF·BEDTIME_RESIDUAL. RECORD_REMINDER 는 예정(features.md).
 */
@Service
@RequiredArgsConstructor
public class NotificationPlanService {

    /** 섭취 마감 알림 리드 타임. 제품값이라 calc/ 상수가 아니다. */
    static final Duration CUTOFF_LEAD = Duration.ofMinutes(30);
    /** 취침 잔량 예고 리드 타임. 임계값은 마감 계산과 같은 {@link Pharmacokinetics#BEDTIME_SAFE_THRESHOLD_MG}. */
    static final Duration BEDTIME_RESIDUAL_LEAD = Duration.ofMinutes(60);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserSettingsService settingsService;
    private final CaffeineService caffeineService;

    /** now 이후에 울릴 항목만, fireAt 오름차순. getOrCreate 가 첫 접근 시 설정 행을 저장하므로 readOnly 트랜잭션을 걸지 않는다. */
    @Transactional
    public NotificationPlanResponse plan(Long userId, Instant now, ZoneId zone) {
        UserSettings settings = settingsService.getOrCreate(userId);
        List<NotificationItem> items = new ArrayList<>();
        if (settings.isNotifyCutoff() || settings.isNotifyBedtimeResidual()) {
            CaffeineTodayResponse today = caffeineService.today(userId, now, zone);
            if (settings.isNotifyCutoff()) {
                cutoffItem(today, now, zone).ifPresent(items::add);
            }
            if (settings.isNotifyBedtimeResidual()) {
                bedtimeResidualItem(today, now, zone).ifPresent(items::add);
            }
        }
        items.sort(Comparator.comparing(NotificationItem::fireAt));
        return new NotificationPlanResponse(now, List.copyOf(items));
    }

    /**
     * 섭취 마감 알림: 오늘 현황의 마감시각(cutoff) {@link #CUTOFF_LEAD} 전.
     * 마감이 없거나(SAFE_ANYTIME·ALREADY_EXCEEDED) 리드 타임 전 시각이 이미 지났으면 없음 —
     * 홈 화면이 마감시각을 이미 보여주므로 중복 안내하지 않는다.
     */
    private static Optional<NotificationItem> cutoffItem(CaffeineTodayResponse today, Instant now, ZoneId zone) {
        if (!CutoffResult.Status.CUTOFF.name().equals(today.cutoff().status())) {
            return Optional.empty();
        }
        Instant cutoff = today.cutoff().cutoff();
        Instant fireAt = cutoff.minus(CUTOFF_LEAD);
        if (!fireAt.isAfter(now)) {
            return Optional.empty();
        }
        // Instant 는 시간대 없이는 HH:mm 포맷이 불가 — 요청 tz 로컬 시각으로 표기
        DateTimeFormatter local = HH_MM.withZone(zone);
        String title = "섭취 마감 " + CUTOFF_LEAD.toMinutes() + "분 전";
        String body = "%s 취침 기준, %dmg를 마실 수 있는 마지막 시각은 %s이에요."
                .formatted(local.format(today.bedtime()), today.referenceDoseMg(), local.format(cutoff));
        return Optional.of(new NotificationItem(NotificationType.CUTOFF.name(), fireAt, title, body));
    }

    /**
     * 취침 잔량 예고: 취침 시 예상 잔량이 {@link Pharmacokinetics#BEDTIME_SAFE_THRESHOLD_MG} 이상이면
     * 취침 {@link #BEDTIME_RESIDUAL_LEAD} 전. 이 임계값은 마감 계산의 ALREADY_EXCEEDED 판정선과 같으므로
     * CUTOFF 항목과는 같은 계획에 함께 나오지 않는다(여유 있으면 마감 알림, 초과면 잔량 예고).
     */
    private static Optional<NotificationItem> bedtimeResidualItem(
            CaffeineTodayResponse today, Instant now, ZoneId zone) {
        double predicted = today.predictedAtBedtime();
        if (predicted < Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG) {
            return Optional.empty();
        }
        Instant fireAt = today.bedtime().minus(BEDTIME_RESIDUAL_LEAD);
        if (!fireAt.isAfter(now)) {
            return Optional.empty();
        }
        DateTimeFormatter local = HH_MM.withZone(zone);
        String title = "취침 " + BEDTIME_RESIDUAL_LEAD.toMinutes() + "분 전 잔량 예고";
        String body = "%s 취침 시 카페인이 약 %dmg 남아 있을 것으로 보여요."
                .formatted(local.format(today.bedtime()), Math.round(predicted));
        return Optional.of(new NotificationItem(NotificationType.BEDTIME_RESIDUAL.name(), fireAt, title, body));
    }
}
