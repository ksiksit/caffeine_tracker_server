package com.jongbeom.server.settings;

import com.jongbeom.server.settings.dto.UpdateSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserSettingsRepository repository;

    /** 설정 조회. 없으면 기본값으로 생성해 반환(GET 이 곧 보장). */
    @Transactional
    public UserSettings getOrCreate(Long userId) {
        return repository.findById(userId)
                .orElseGet(() -> repository.save(UserSettings.createDefault(userId)));
    }

    @Transactional
    public UserSettings update(Long userId, UpdateSettingsRequest req) {
        UserSettings settings = getOrCreate(userId);
        settings.update(req.halfLife(), req.condition(), req.bedtimeHour(), req.bedtimeMinute(),
                req.referenceDoseMg(), req.isLearningEnabled());
        if (req.hasLearnedSeed()) {
            settings.seedLearned(req.learnedMean(), req.learnedVariance(), req.lastLearnedDate());
        }
        return repository.save(settings);
    }
}
