package com.jongbeom.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시간 의존 로직용 주입형 Clock 설정. */
@Configuration
public class ClockConfig {

    /**
     * 시간 의존 로직(학습 후보 날짜 등)을 테스트에서 {@code @MockitoBean Clock}으로 고정할 수 있도록 제공.
     * 주의: JwtTokenProvider·RefreshTokenService 는 이 빈 대신 자체 생성자 Clock 을 쓴다 —
     * 빈 주입으로 통일하면 mock Clock 이 토큰 발급 시각에도 영향을 주므로(동작 변경) 보류.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
