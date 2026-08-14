package com.jongbeom.server.calc;

/**
 * 수면 단계. iOS {@code SleepStage}(HealthKit) 이관.
 * hkValue 는 HKCategoryValueSleepAnalysis rawValue: inBed=0, asleepUnspecified=1, awake=2,
 * asleepCore=3, asleepDeep=4, asleepREM=5. priority 는 병합 시 세분화 우선순위(높을수록 우선).
 *
 * <p>선언 순서는 iOS {@code SleepStage.allCases}(inBed→awake→core→deep→rem→unspecified)와 일치 —
 * stageBreakdown 정렬 순서가 동일해야 한다.
 */
public enum SleepStage {
    IN_BED(0, 0, false),
    AWAKE(2, 1, false),
    ASLEEP_CORE(3, 3, true),
    ASLEEP_DEEP(4, 5, true),
    ASLEEP_REM(5, 4, true),
    ASLEEP_UNSPECIFIED(1, 2, true);

    private final int hkValue;
    private final int priority;
    private final boolean asleep;

    SleepStage(int hkValue, int priority, boolean asleep) {
        this.hkValue = hkValue;
        this.priority = priority;
        this.asleep = asleep;
    }

    public int hkValue() {
        return hkValue;
    }

    public int priority() {
        return priority;
    }

    public boolean isAsleep() {
        return asleep;
    }

    /** HK rawValue → 단계. 알 수 없는 값은 asleepUnspecified (← iOS SleepStage.init(from:) default). */
    public static SleepStage fromHkValue(int value) {
        // 위 enum 테이블의 hkValue와 동기화 유지
        return switch (value) {
            case 0 -> IN_BED;
            case 2 -> AWAKE;
            case 3 -> ASLEEP_CORE;
            case 4 -> ASLEEP_DEEP;
            case 5 -> ASLEEP_REM;
            default -> ASLEEP_UNSPECIFIED;
        };
    }

    /** 병합용 우선순위 편의 메서드 — {@code fromHkValue(v).priority()}와 동일. */
    public static int stagePriority(int hkValue) {
        return fromHkValue(hkValue).priority;
    }
}
