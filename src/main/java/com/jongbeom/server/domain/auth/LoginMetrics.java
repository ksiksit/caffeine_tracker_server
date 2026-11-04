package com.jongbeom.server.domain.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 로그인 결과 메트릭 {@code caffeine.auth.logins{outcome}}. 비밀번호 검증까지 간 시도만 센다.
 *
 * <ul>
 *   <li>{@code SUCCESS} — 로그인 성공</li>
 *   <li>{@code WRONG_PASSWORD} — 가입된 이메일, 비밀번호 불일치</li>
 *   <li>{@code UNKNOWN_EMAIL} — 가입되지 않은 이메일. 응답은 {@code WRONG_PASSWORD}와 똑같은 401 이라 HTTP 메트릭으로는
 *       둘을 나눌 수 없다. 이 값만 크게 늘면 남의 이메일 목록을 대입하는 공격(credential stuffing)을 의심한다.</li>
 * </ul>
 *
 * 시도 제한에 막힌 요청({@link LoginAttemptLimiter}, 429)은 검증 전에 끝나므로 여기 없다 — HTTP 메트릭의
 * {@code status="429"}로 본다. 관리 포트(루프백 전용)에서만 노출되므로 가입 여부 구분이 외부로 드러나지 않는다.
 * 메트릭 이름·태그를 바꾸면 Grafana 대시보드 JSON 과 운영 가이드도 같이 바꾼다.
 */
@Component
public class LoginMetrics {

    public static final String LOGINS = "caffeine.auth.logins";
    public static final String OUTCOME_TAG = "outcome";

    public enum Outcome { SUCCESS, WRONG_PASSWORD, UNKNOWN_EMAIL }

    private final Counter success;
    private final Counter wrongPassword;
    private final Counter unknownEmail;

    public LoginMetrics(MeterRegistry registry) {
        success = counter(registry, Outcome.SUCCESS);
        wrongPassword = counter(registry, Outcome.WRONG_PASSWORD);
        unknownEmail = counter(registry, Outcome.UNKNOWN_EMAIL);
    }

    public void record(Outcome outcome) {
        switch (outcome) {
            case SUCCESS -> success.increment();
            case WRONG_PASSWORD -> wrongPassword.increment();
            case UNKNOWN_EMAIL -> unknownEmail.increment();
        }
    }

    private static Counter counter(MeterRegistry registry, Outcome outcome) {
        return Counter.builder(LOGINS)
                .description("비밀번호 검증까지 간 로그인 시도 수 (시도 제한 429 는 제외)")
                .tag(OUTCOME_TAG, outcome.name())
                .register(registry);
    }
}
