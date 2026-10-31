package com.jongbeom.server.domain.calc.time;

import java.time.Instant;

/**
 * 두 절대시각 사이 경과량. 밀리초 차(long)를 double 단위 상수로 나눈다.
 *
 * <p>계산 형태(밀리초 차 → 한 번의 나눗셈)를 바꾸지 말 것 — {@link java.time.Duration} 전환 등으로
 * 연산 순서가 달라지면 iOS 골든과 부동소수점 결과가 어긋난다.
 */
public final class Elapsed {

    private Elapsed() {
    }

    public static final double MILLIS_PER_HOUR = 3_600_000.0;
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** from → to 경과 초. to가 앞서면 음수. */
    public static double seconds(Instant from, Instant to) {
        return (to.toEpochMilli() - from.toEpochMilli()) / MILLIS_PER_SECOND;
    }

    /** from → to 경과 시간(hour). to가 앞서면 음수. */
    public static double hours(Instant from, Instant to) {
        return (to.toEpochMilli() - from.toEpochMilli()) / MILLIS_PER_HOUR;
    }
}
