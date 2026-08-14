package com.jongbeom.server.learning;

import com.jongbeom.server.calc.BedtimeExtractor;

/** 학습을 건너뛴 사유. iOS {@code LearningSkipReason} 이관(사용자 표시 메시지 포함). */
public enum LearningSkipReason {
    LEARNING_DISABLED("자동 학습이 꺼져 있어요."),
    NO_SLEEP_SUMMARY("최근 수면 기록을 찾지 못했어요."),
    SLEEP_TOO_SHORT("최근 수면 시간이 4시간보다 짧아 학습하지 않았어요."),
    MISSING_BEDTIME("취침 시작 시각을 찾지 못해 SOL을 계산할 수 없어요."),
    INVALID_SOL("수면 잠복기 값이 유효 범위를 벗어났어요."),
    ALREADY_LEARNED("최근 수면 기록은 이미 학습에 반영됐어요."),
    INVALID_PRIOR("현재 반감기 설정값이 유효하지 않아 학습하지 않았어요."),
    INSUFFICIENT_CAFFEINE_RESIDUAL("취침 시 카페인 잔량이 너무 적어 학습할 정보가 부족했어요."),
    INVALID_OBSERVATION("학습 관측값을 저장할 수 없어 건너뛰었어요.");

    private final String message;

    LearningSkipReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    // public: 호출부(learning.service.LearningService)가 하위 패키지로 분리되면서 패키지 경계를 넘게 됨
    public static LearningSkipReason from(BedtimeExtractor.Gate gate) {
        return switch (gate) {
            case SLEEP_TOO_SHORT -> SLEEP_TOO_SHORT;
            case MISSING_BEDTIME -> MISSING_BEDTIME;
            case INVALID_SOL -> INVALID_SOL;
        };
    }
}
