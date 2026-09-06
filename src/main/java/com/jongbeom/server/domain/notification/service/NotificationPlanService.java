package com.jongbeom.server.domain.notification.service;

import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 알림 계획 계산. 서버는 푸시를 보내지 않고(운영 EC2 외부 인터넷 불가) 계획만 내려준다 — CLAUDE.md 도메인 계약.
 * 종류별 생성은 아직 없다: 설정만 읽어 빈 목록을 돌려준다. 종류를 구현할 때 {@code zone} 으로 로컬 달력 연산을 한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationPlanService {

    private final UserSettingsService settingsService;

    /** getOrCreate 가 첫 접근 시 설정 행을 저장하므로 readOnly 트랜잭션을 걸지 않는다. */
    @Transactional
    public NotificationPlanResponse plan(Long userId, Instant now, ZoneId zone) {
        settingsService.getOrCreate(userId);
        return new NotificationPlanResponse(now, List.of());
    }
}
