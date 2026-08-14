package com.jongbeom.server.settings.repository;

import com.jongbeom.server.settings.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
}
