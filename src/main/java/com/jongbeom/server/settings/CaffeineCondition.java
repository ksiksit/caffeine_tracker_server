package com.jongbeom.server.settings;

/**
 * 건강 상태별 카페인 반감기 보정. multiplier 값(0.5/1.0/2.0/2.5)은 약리학 근거 기반 — 임의 변경 금지.
 * rawValue 는 iOS {@code CaffeineCondition} 과 1:1 일치(클라가 Int 로 전송).
 */
public enum CaffeineCondition {
    NONE(0, 1.0),
    SMOKER(1, 0.5),
    ORAL_CONTRACEPTIVE(2, 2.0),
    PREGNANT(3, 2.5);

    private final int rawValue;
    private final double multiplier;

    CaffeineCondition(int rawValue, double multiplier) {
        this.rawValue = rawValue;
        this.multiplier = multiplier;
    }

    public int rawValue() {
        return rawValue;
    }

    public double multiplier() {
        return multiplier;
    }

    public static CaffeineCondition fromRawValue(int rawValue) {
        for (CaffeineCondition c : values()) {
            if (c.rawValue == rawValue) {
                return c;
            }
        }
        return NONE;
    }
}
