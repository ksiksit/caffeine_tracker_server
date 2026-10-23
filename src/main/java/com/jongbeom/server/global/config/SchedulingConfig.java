package com.jongbeom.server.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @Scheduled} 활성화(현재: RefreshTokenService.purgeStale). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
