package com.jongbeom.server.settings;

import com.jongbeom.server.settings.dto.UpdateSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 유저 설정 조회·갱신. 설정 행은 첫 조회 시점에 기본값으로 생성되는 upsert 모델. */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;

    /** 설정 조회. 없으면 기본값으로 생성해 반환(GET 이 곧 보장). */
    @Transactional
    public UserSettings getOrCreate(Long userId) {
        return userSettingsRepository.findById(userId)
                .orElseGet(() -> userSettingsRepository.save(UserSettings.createDefault(userId)));
    }

    @Transactional
    public UserSettings update(Long userId, UpdateSettingsRequest request) {
        UserSettings settings = getOrCreate(userId);
        settings.update(request.halfLife(), request.condition(), request.bedtimeHour(),
                request.bedtimeMinute(), request.referenceDoseMg(), request.isLearningEnabled());
        // learned 3종이 모두 온 경우에만 1회 시드 — 부분 제공 규칙은 UpdateSettingsRequest javadoc 참조
        if (request.hasLearnedSeed()) {
            settings.seedLearned(request.learnedMean(), request.learnedVariance(), request.lastLearnedDate());
        }
        // getOrCreate 가 영속 엔티티를 반환하므로 변경은 dirty checking 으로도 반영된다 — save 는 의도 명시용
        return userSettingsRepository.save(settings);
    }
}
