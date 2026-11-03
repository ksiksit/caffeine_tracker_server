package com.jongbeom.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jongbeom.server.domain.auth.exception.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    private void acquireTimes(String email, int times) {
        for (int i = 0; i < times; i++) {
            limiter.acquire(email);
        }
    }

    @Test
    void 한도까지는_통과하고_그다음_시도는_막힌다() {
        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);

        assertThatThrownBy(() -> limiter.acquire("a@b.com"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void 막힌_동안의_시도는_해제를_늦추지_않는다() {
        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);
        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> limiter.acquire("a@b.com")).isInstanceOf(TooManyLoginAttemptsException.class);

        clock.advance(Duration.ofMinutes(1)); // 첫 시도로부터 정확히 15분
        assertThatCode(() -> limiter.acquire("a@b.com")).doesNotThrowAnyException();
    }

    @Test
    void 창이_지나면_다시_처음부터_센다() {
        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);
        clock.advance(LoginAttemptLimiter.WINDOW);

        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);
        assertThatThrownBy(() -> limiter.acquire("a@b.com")).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void 성공으로_reset하면_기록이_사라진다() {
        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);
        limiter.reset("a@b.com");

        assertThatCode(() -> acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS)).doesNotThrowAnyException();
    }

    @Test
    void 이메일_대소문자와_앞뒤공백은_같은_키() {
        acquireTimes("A@B.com", 3);
        acquireTimes(" a@b.COM ", 2);

        assertThatThrownBy(() -> limiter.acquire("a@b.com")).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void 다른_이메일은_서로_영향이_없다() {
        acquireTimes("a@b.com", LoginAttemptLimiter.MAX_ATTEMPTS);

        assertThatCode(() -> limiter.acquire("c@d.com")).doesNotThrowAnyException();
    }

    @Test
    void 동시에_보낸_시도도_한도만큼만_통과한다() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire("a@b.com");
                        return true;
                    } catch (TooManyLoginAttemptsException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int passed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(LoginAttemptLimiter.MAX_ATTEMPTS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 테스트에서 시각을 앞으로 돌리는 Clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
