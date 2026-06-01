package com.jongbeom.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** 시간 의존 로직(학습 후보 날짜 등)을 테스트에서 고정할 수 있도록 주입형 Clock 제공. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
