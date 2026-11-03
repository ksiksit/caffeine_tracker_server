package com.jongbeom.server.domain.settings.service;

import com.jongbeom.server.domain.settings.dto.NotificationSettingsRequest;
import com.jongbeom.server.domain.settings.dto.UpdateSettingsRequest;
import com.jongbeom.server.domain.settings.entity.UserSettings;
import com.jongbeom.server.domain.settings.repository.UserSettingsRepository;
import com.jongbeom.server.domain.user.UserWriteLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저 설정 조회·갱신. 설정 행은 가입 트랜잭션에서 기본값으로 만든다({@link #createDefault}).
 * {@link #getOrCreate}의 생성 분기는 이 규칙 이전에 가입해 행이 없는 계정용 폴백이다.
 */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final UserWriteLock userWriteLock;

    /**
     * 가입 시 기본 설정 행 생성. 첫 접속 때 설정을 읽는 요청 여러 개가 동시에 와도
     * 각자 행을 만들려다 PK 가 충돌하지 않도록, 행은 가입과 같은 트랜잭션에서 미리 만든다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefault(Long userId) {
        userSettingsRepository.save(UserSettings.createDefault(userId));
    }

    /** 설정 조회. 없으면 기본값으로 생성해 반환(GET 이 곧 보장). */
    @Transactional
    public UserSettings getOrCreate(Long userId) {
        return userSettingsRepository.findById(userId)
                .orElseGet(() -> userSettingsRepository.save(UserSettings.createDefault(userId)));
    }

    /** 같은 사용자의 학습 실행과 겹치면 한쪽 갱신이 유실되므로 사용자 쓰기 잠금 뒤에 읽는다. */
    @Transactional
    public UserSettings update(Long userId, UpdateSettingsRequest request) {
        userWriteLock.lock(userId);
        UserSettings settings = getOrCreate(userId);
        settings.update(request.halfLife(), request.condition(), request.bedtimeHour(),
                request.bedtimeMinute(), request.referenceDoseMg(), request.isLearningEnabled());
        NotificationSettingsRequest notifications = request.notifications();
        settings.updateNotifications(
                notifications.cutoff(), notifications.bedtimeResidual(), notifications.recordReminder(),
                notifications.cafeNearby());
        // learned 3종이 모두 온 경우에만 1회 시드 — 부분 제공 규칙은 UpdateSettingsRequest javadoc 참조
        if (request.hasLearnedSeed()) {
            settings.seedLearned(request.learnedMean(), request.learnedVariance(), request.lastLearnedDate());
        }
        // getOrCreate 가 영속 엔티티를 반환하므로 변경은 dirty checking 으로도 반영된다 — save 는 의도 명시용
        return userSettingsRepository.save(settings);
    }
}
