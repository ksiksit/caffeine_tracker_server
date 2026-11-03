package com.jongbeom.server.domain.auth;

import com.jongbeom.server.domain.auth.exception.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이메일 단위 로그인 시도 제한 — 비밀번호 무차별 대입 방어.
 * 첫 시도부터 {@link #WINDOW} 안에 {@link #MAX_ATTEMPTS}번 실패하면 그 창이 끝날 때까지 로그인을 막는다(429).
 * 성공하면 그 이메일의 기록을 지운다.
 *
 * <ul>
 *   <li><b>시도를 먼저 센다.</b> 비밀번호 검증(bcrypt) 전에 원자적으로 1 올리고, 성공하면 지운다.
 *       검증 후에 세면 동시에 보낸 추측들이 모두 확인을 통과해 한도를 넘는다.</li>
 *   <li><b>없는 이메일도 똑같이 센다.</b> 다르게 세면 429 여부로 가입 여부가 드러난다.</li>
 *   <li><b>막힌 동안에는 맞는 비밀번호도 429.</b> 아니면 429/200 차이로 비밀번호를 맞혔는지 알 수 있다.</li>
 *   <li>키는 이메일만 쓴다. IP 는 리버스 프록시·Docker 포워딩 뒤에서 모든 요청이 같은 주소로 보일 수 있어서
 *       IP 로 막으면 전체 사용자가 함께 막힐 수 있다.</li>
 *   <li>메모리 보관 — 서버가 EC2 한 대라 충분하다. 재시작하면 기록이 사라진다. 여러 대로 늘리면 공유 저장소가 필요하다.</li>
 * </ul>
 * 대가: 남의 이메일로 일부러 틀려 그 계정을 15분 동안 막을 수 있다(계정 잠금형 서비스 거부). 창이 짧아 감수한다.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** 만료 항목 정리를 시작하는 크기. 정리는 기록할 때만 하므로 그 사이엔 이 크기를 잠시 넘을 수 있다. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attemptsByEmail = new ConcurrentHashMap<>();

    /** 창 시작 시각과 그 안의 시도 수. 수는 MAX_ATTEMPTS + 1 에서 멈춘다(그 값이 "막힘"). */
    private record Attempts(Instant windowStart, int count) {
        boolean isExpired(Instant now) {
            return !now.isBefore(windowStart.plus(WINDOW));
        }
    }

    // ClockConfig 빈을 쓰지 않는 이유는 JwtTokenProvider 와 같다 — 테스트의 mock Clock 이 로그인에 영향을 주지 않게.
    @Autowired
    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 시도 1회를 기록한다. 이미 한도를 채웠으면 기록하지 않고 {@link TooManyLoginAttemptsException}. */
    public void acquire(String email) {
        Instant now = clock.instant();
        Attempts attempts = attemptsByEmail.compute(key(email), (k, current) -> {
            if (current == null || current.isExpired(now)) {
                return new Attempts(now, 1);
            }
            return new Attempts(current.windowStart(), Math.min(current.count() + 1, MAX_ATTEMPTS + 1));
        });
        if (attemptsByEmail.size() > SWEEP_THRESHOLD) {
            attemptsByEmail.values().removeIf(a -> a.isExpired(now));
        }
        if (attempts.count() > MAX_ATTEMPTS) {
            throw new TooManyLoginAttemptsException(attempts.windowStart().plus(WINDOW));
        }
    }

    /** 로그인 성공 — 그 이메일의 기록을 지운다. */
    public void reset(String email) {
        attemptsByEmail.remove(key(email));
    }

    /** 전체 기록 삭제. 통합 테스트 격리용(스프링 컨텍스트가 테스트 사이에 재사용되므로). */
    public void clear() {
        attemptsByEmail.clear();
    }

    /** 이메일 비교는 DB collation(utf8mb4_unicode_ci)처럼 대소문자를 구분하지 않는다. */
    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
