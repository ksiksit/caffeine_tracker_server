package com.jongbeom.server.domain.settings.repository;

import com.jongbeom.server.domain.settings.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
}
