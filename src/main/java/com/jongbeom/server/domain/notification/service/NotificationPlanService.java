package com.jongbeom.server.domain.notification.service;

import com.jongbeom.server.domain.caffeine.CaffeineStatus;
import com.jongbeom.server.domain.caffeine.service.CaffeineService;
import com.jongbeom.server.domain.calc.time.LocalCalendar;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.Dose;
import com.jongbeom.server.domain.notification.CafeNearbyReason;
import com.jongbeom.server.domain.notification.NotificationType;
import com.jongbeom.server.domain.notification.UsualFirstRecordTime;
import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse;
import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse.CafeNearbyWindow;
import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse.NotificationItem;
import com.jongbeom.server.domain.settings.SettingsSnapshot;
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
 * 종류별 생성 규칙의 계약은 docs/api/api.md 알림 절이며, 여기의 상수·문구를 바꾸면 그 표도 같이 바꾼다.
 * 종류 세 개(CUTOFF·BEDTIME_RESIDUAL·RECORD_REMINDER) 모두 구현. 카페 근처 알림은 시각이 아니라 금지 구간으로 내려준다.
 */
@Service
@RequiredArgsConstructor
public class NotificationPlanService {

    /** 섭취 마감 알림 리드 타임. 제품값이라 calc/ 상수가 아니다. */
    static final Duration CUTOFF_LEAD = Duration.ofMinutes(30);
    /** 취침 잔량 예고 리드 타임. 임계값은 마감 계산과 같은 {@link Pharmacokinetics#BEDTIME_SAFE_THRESHOLD_MG}. */
    static final Duration BEDTIME_RESIDUAL_LEAD = Duration.ofMinutes(60);
    /** 기록 리마인더: 최근 이 기간의 기록으로 "평소 첫 기록 시각"을 구한다. */
    static final Duration RECORD_REMINDER_HISTORY = Duration.ofDays(14);
    /** 기록 리마인더: 그 기간에 기록 있는 날이 이 수 미만이면 리마인드하지 않는다(기록이 드문 사용자 제외). */
    static final int RECORD_REMINDER_MIN_DAYS = 5;
    /** 기록 리마인더: 평소 첫 기록 시각에서 이만큼 지나도 기록이 없을 때. */
    static final Duration RECORD_REMINDER_DELAY = Duration.ofHours(2);
    /** 카페 근처 알림 제목. 사유와 무관하게 같다 — 사용자가 알아야 할 건 "지금 카페 앞"이라는 사실. */
    static final String CAFE_NEARBY_TITLE = "카페 근처예요";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserSettingsService settingsService;
    private final CaffeineService caffeineService;

    /** now 이후에 울릴 항목만, fireAt 오름차순. 설정 조회가 첫 접근 시 설정 행을 저장하므로 readOnly 트랜잭션을 걸지 않는다. */
    @Transactional
    public NotificationPlanResponse plan(Long userId, Instant now, ZoneId zone) {
        SettingsSnapshot settings = settingsService.snapshot(userId);
        List<NotificationItem> items = new ArrayList<>();
        List<CafeNearbyWindow> windows = new ArrayList<>();
        if (settings.notifyCutoff() || settings.notifyBedtimeResidual() || settings.notifyCafeNearby()) {
            CaffeineStatus today = caffeineService.status(userId, now, zone);
            if (settings.notifyCutoff()) {
                cutoffItem(today, now, zone).ifPresent(items::add);
            }
            if (settings.notifyBedtimeResidual()) {
                bedtimeResidualItem(today, now, zone).ifPresent(items::add);
            }
            if (settings.notifyCafeNearby()) {
                cafeNearbyWindow(today, now, zone).ifPresent(windows::add);
                // 새 기록 없는 다음 날의 기본 구간(최근 24시간 잔량은 포함) — 앱을 하루 종일 열지 않아도 동작하게. 다음 날 기록은 앱에서만 생기고
                // 그때 앱이 재조회하므로, "기록이 없다"는 가정은 틀리는 순간 교체된다.
                Instant nextDay = LocalCalendar.chartEnd(now, zone);
                cafeNearbyWindow(caffeineService.status(userId, nextDay, zone), nextDay, zone).ifPresent(windows::add);
            }
        }
        if (settings.notifyRecordReminder()) {
            items.addAll(recordReminderItems(userId, now, zone));
        }
        // 안정 정렬 — fireAt 동률이면 위 삽입 순서 유지
        items.sort(Comparator.comparing(NotificationItem::fireAt));
        return new NotificationPlanResponse(now, List.copyOf(items), List.copyOf(windows));
    }

    /**
     * 카페 근처 알림 금지 구간: [시작, 그날 하루 경계(다음 05:00)). 하루에 하나, 사유가 겹치면 {@link CafeNearbyReason} 선언 순.
     * <ul>
     *   <li>ALREADY_EXCEEDED — 취침 시 예상 잔량이 이미 50mg 이상. 지금({@code at})부터</li>
     *   <li>DAILY_LIMIT_EXCEEDED — 오늘 섭취량이 400mg 초과. 지금부터</li>
     *   <li>CUTOFF_PASSED — 섭취 마감 시각이 있음. 마감 시각부터(이미 지났으면 과거 시각 그대로)</li>
     *   <li>마감 없음(SAFE_ANYTIME)이고 400mg 이하면 구간 없음</li>
     * </ul>
     * 끝을 취침이 아니라 05:00으로 둔 건 취침 뒤 새벽 섭취도 막아야 하고, 서버의 "오늘" 경계와 맞추기 위해서다.
     * 00:00~05:00 의 {@code at}은 전날 섭취일에 속하므로 끝은 그날(달력 당일) 05:00 이다.
     * 마감 시각이 지나면 기준 용량 한 잔이 곧 취침 시 50mg 초과이므로, 문구의 "기준을 넘어요"는 계산 없이 참이다.
     */
    private static Optional<CafeNearbyWindow> cafeNearbyWindow(CaffeineStatus today, Instant at, ZoneId zone) {
        Instant until = LocalCalendar.chartEnd(at, zone);
        CutoffResult.Status status = today.cutoff().status();
        if (status == CutoffResult.Status.ALREADY_EXCEEDED) {
            String body = "지금도 취침 때 카페인이 약 %dmg 남을 것 같아요. 오늘은 디카페인으로 골라 주세요."
                    .formatted(Math.round(today.predictedAtBedtime()));
            return Optional.of(new CafeNearbyWindow(
                    CafeNearbyReason.ALREADY_EXCEEDED.name(), at, until, CAFE_NEARBY_TITLE, body));
        }
        if (today.overDailyLimit()) {
            String body = "오늘 이미 %dmg를 마셨어요(하루 권장 %dmg). 오늘은 디카페인으로 골라 주세요."
                    .formatted(today.todayTotal(), Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG);
            return Optional.of(new CafeNearbyWindow(
                    CafeNearbyReason.DAILY_LIMIT_EXCEEDED.name(), at, until, CAFE_NEARBY_TITLE, body));
        }
        if (status == CutoffResult.Status.CUTOFF) {
            Instant cutoff = today.cutoff().cutoff();
            String body = "오늘 마감 %s이 지났어요. 지금 %dmg 한 잔이면 취침 때 기준(%dmg)을 넘어요. 들어간다면 디카페인으로!"
                    .formatted(HH_MM.withZone(zone).format(cutoff), today.referenceDoseMg(),
                            Math.round(Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG));
            return Optional.of(new CafeNearbyWindow(
                    CafeNearbyReason.CUTOFF_PASSED.name(), cutoff, until, CAFE_NEARBY_TITLE, body));
        }
        return Optional.empty();
    }

    /**
     * 섭취 마감 알림: 오늘 현황의 마감시각(cutoff) {@link #CUTOFF_LEAD} 전.
     * 마감이 없거나(SAFE_ANYTIME·ALREADY_EXCEEDED) 리드 타임 전 시각이 이미 지났으면 없음 —
     * 홈 화면이 마감시각을 이미 보여주므로 중복 안내하지 않는다.
     */
    private static Optional<NotificationItem> cutoffItem(CaffeineStatus today, Instant now, ZoneId zone) {
        if (today.cutoff().status() != CutoffResult.Status.CUTOFF) {
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
            CaffeineStatus today, Instant now, ZoneId zone) {
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

    /**
     * 기록 리마인더: 그 섭취일(05:00 경계) 기록이 없고 직전 {@link #RECORD_REMINDER_HISTORY} 중 기록 있는 날이
     * {@link #RECORD_REMINDER_MIN_DAYS} 이상이면 "평소 첫 기록 시각({@link UsualFirstRecordTime}) + {@link #RECORD_REMINDER_DELAY}" 에.
     * 그 섭취일 창(05:00 + 24h) 밖이거나 이미 지났으면 없음.
     *
     * <p>대상 섭취일은 오늘({@code now}가 속한 섭취일) 하나, 00:00~05:00 이면 다가오는 섭취일(당일 05:00 시작)도 함께다.
     * 00:00~05:00 의 오늘은 전날 섭취일이라 평소 시각이 대개 지났고, 앱은 시각 경과로 계획을 재조회하지 않으므로
     * 마지막 사용이 새벽이어도 그날 리마인더가 울리게 미리 넣는다. 다가오는 섭취일은 아직 시작 전이라 기록이 없고
     * (새벽 기록은 전날 섭취일 소속), 결과는 05:00 에 만든 계획과 같다.
     */
    private List<NotificationItem> recordReminderItems(Long userId, Instant now, ZoneId zone) {
        Instant todayStart = LocalCalendar.chartStart(now, zone);
        // 달력 당일 05:00 — 00:00~05:00 이면 아직 오지 않은 다가오는 섭취일의 시작(05:00 에 만든 계획의 chartStart 와 같은 산술)
        Instant upcomingStart = now.atZone(zone).toLocalDate().atStartOfDay(zone)
                .plusHours(LocalCalendar.CHART_START_HOUR).toInstant();
        // 쿼리 1회(오름차순). 다가오는 섭취일의 이력 구간은 오늘 이력 구간의 시작 이후라 같은 결과로 판단한다.
        List<Instant> timestamps = caffeineService.dosesSince(userId, todayStart.minus(RECORD_REMINDER_HISTORY))
                .stream().map(Dose::timestamp).toList();
        List<NotificationItem> items = new ArrayList<>();
        recordReminderItem(todayStart, timestamps, now, zone).ifPresent(items::add);
        if (upcomingStart.isAfter(now)) {
            recordReminderItem(upcomingStart, timestamps, now, zone).ifPresent(items::add);
        }
        return items;
    }

    /** dayStart 에 시작하는 섭취일 하나의 리마인더. timestamps 는 오름차순. */
    private static Optional<NotificationItem> recordReminderItem(
            Instant dayStart, List<Instant> timestamps, Instant now, ZoneId zone) {
        Instant dayEnd = dayStart.plus(Duration.ofHours(LocalCalendar.CHART_FORWARD_HOURS));
        Instant historyStart = dayStart.minus(RECORD_REMINDER_HISTORY);
        // 경계 중복을 피하려고 [historyStart, dayStart) 를 이력, dayStart 이후를 그날 기록으로 나눈다
        List<Instant> history = new ArrayList<>();
        for (Instant ts : timestamps) {
            if (ts.isBefore(historyStart)) {
                continue;
            }
            if (!ts.isBefore(dayStart)) {
                return Optional.empty(); // 그날 기록 있음
            }
            history.add(ts);
        }
        Optional<Duration> usualOffset = UsualFirstRecordTime.median(history, zone, RECORD_REMINDER_MIN_DAYS);
        if (usualOffset.isEmpty()) {
            return Optional.empty();
        }
        Instant usual = dayStart.plus(usualOffset.get());
        Instant fireAt = usual.plus(RECORD_REMINDER_DELAY);
        if (!fireAt.isAfter(now) || !fireAt.isBefore(dayEnd)) {
            return Optional.empty();
        }
        String body = "평소 %s쯤 첫 잔을 기록했어요. 마셨다면 잊지 말고 기록해 주세요."
                .formatted(HH_MM.withZone(zone).format(usual));
        return Optional.of(new NotificationItem(
                NotificationType.RECORD_REMINDER.name(), fireAt, "오늘 카페인 기록이 없어요", body));
    }
}
