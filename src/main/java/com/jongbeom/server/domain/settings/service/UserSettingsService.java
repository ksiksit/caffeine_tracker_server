package com.jongbeom.server.domain.settings.service;

import com.jongbeom.server.domain.settings.SettingsSnapshot;
import com.jongbeom.server.domain.settings.dto.NotificationSettingsRequest;
import com.jongbeom.server.domain.settings.dto.UpdateSettingsRequest;
import com.jongbeom.server.domain.settings.entity.CaffeineCondition;
import com.jongbeom.server.domain.settings.entity.UserSettings;
import com.jongbeom.server.domain.settings.repository.UserSettingsRepository;
import com.jongbeom.server.domain.user.UserWriteLock;
import java.time.LocalDate;
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

    /** 다른 도메인용 조회. 엔티티 대신 읽기 전용 사본을 준다 — 없으면 {@link #getOrCreate}와 같이 기본값으로 생성. */
    @Transactional
    public SettingsSnapshot snapshot(Long userId) {
        return toSnapshot(getOrCreate(userId));
    }

    /**
     * 베이지안 학습 1회 결과 반영. 갱신된 사본을 돌려주므로 호출자는 그 값을 다음 night 의 prior 로 쓴다.
     * 잠금은 호출자(학습 실행)가 트랜잭션 첫 문장으로 이미 잡고 있어야 하므로 그 트랜잭션 안에서만 부른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettingsSnapshot applyLearning(Long userId, double learnedMean, double learnedVariance,
                                          LocalDate lastLearnedDate) {
        UserSettings settings = getOrCreate(userId);
        settings.applyLearning(learnedMean, learnedVariance, lastLearnedDate);
        return toSnapshot(settings);
    }

    private static SettingsSnapshot toSnapshot(UserSettings settings) {
        return new SettingsSnapshot(
                settings.effectiveHalfLifeHours(),
                CaffeineCondition.fromRawValue(settings.getCondition()).multiplier(),
                settings.getBedtimeHour(), settings.getBedtimeMinute(), settings.getReferenceDoseMg(),
                settings.isLearningEnabled(), settings.getLearnedMean(), settings.getLearnedVariance(),
                settings.isNotifyCutoff(), settings.isNotifyBedtimeResidual(),
                settings.isNotifyRecordReminder(), settings.isNotifyCafeNearby());
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
