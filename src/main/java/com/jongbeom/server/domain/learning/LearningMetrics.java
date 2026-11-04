package com.jongbeom.server.domain.learning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 학습 실행 결과 메트릭. {@code POST /api/learning/run}은 학습이 됐든 건너뛰었든 200 이라 HTTP 메트릭으로는 구분되지 않는다.
 *
 * <ul>
 *   <li>{@code caffeine.learning.runs{outcome}} — 실행 1회당 1. 1밤 이상 학습했으면 {@code LEARNED}, 아니면 응답의
 *       {@code skipReason}과 같은 값.</li>
 *   <li>{@code caffeine.learning.nights{outcome}} — 실행이 살펴본 후보 밤 1개당 1. 학습했으면 {@code LEARNED},
 *       아니면 그 밤의 skip 사유. 응답은 가장 최신 밤의 사유 하나만 보여 주므로 다른 밤이 왜 빠졌는지는 여기서 본다.</li>
 * </ul>
 *
 * 사용자 식별자는 태그로 붙이지 않는다(사용자 수만큼 시계열이 늘어난다). 가능한 outcome 을 모두 기동 때 0으로 등록해
 * 아직 일어나지 않은 사유도 Prometheus 에 0 으로 나온다.
 * 메트릭 이름·태그를 바꾸면 {@code monitoring/grafana/dashboards/caffeine-app.json}과 운영 가이드도 같이 바꾼다.
 */
@Component
public class LearningMetrics {

    public static final String RUNS = "caffeine.learning.runs";
    public static final String NIGHTS = "caffeine.learning.nights";
    public static final String OUTCOME_TAG = "outcome";
    public static final String LEARNED = "LEARNED";

    /** 밤 단위로 정해지지 않고 실행 전체가 멈추는 사유 — nights 에는 나오지 않는다. */
    private static final Set<LearningSkipReason> RUN_ONLY_REASONS =
            EnumSet.of(LearningSkipReason.LEARNING_DISABLED, LearningSkipReason.NO_SLEEP_SUMMARY);

    private final Counter runsLearned;
    private final Map<LearningSkipReason, Counter> runsSkipped = new EnumMap<>(LearningSkipReason.class);
    private final Counter nightsLearned;
    private final Map<LearningSkipReason, Counter> nightsSkipped = new EnumMap<>(LearningSkipReason.class);

    public LearningMetrics(MeterRegistry registry) {
        runsLearned = runCounter(registry, LEARNED);
        nightsLearned = nightCounter(registry, LEARNED);
        for (LearningSkipReason reason : LearningSkipReason.values()) {
            runsSkipped.put(reason, runCounter(registry, reason.name()));
            if (!RUN_ONLY_REASONS.contains(reason)) {
                nightsSkipped.put(reason, nightCounter(registry, reason.name()));
            }
        }
    }

    /** 실행 1회. {@code skipReason}이 null 이면 1밤 이상 학습한 실행. */
    public void recordRun(LearningSkipReason skipReason) {
        (skipReason == null ? runsLearned : runsSkipped.get(skipReason)).increment();
    }

    /** 후보 밤 1개. {@code skipReason}이 null 이면 그 밤을 학습했다. */
    public void recordNight(LearningSkipReason skipReason) {
        if (skipReason == null) {
            nightsLearned.increment();
            return;
        }
        Counter counter = nightsSkipped.get(skipReason);
        if (counter == null) {
            throw new IllegalArgumentException("밤 단위 사유가 아니다: " + skipReason);
        }
        counter.increment();
    }

    private static Counter runCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(RUNS)
                .description("반감기 학습 실행 수 (outcome: LEARNED 또는 응답 skipReason)")
                .tag(OUTCOME_TAG, outcome)
                .register(registry);
    }

    private static Counter nightCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(NIGHTS)
                .description("학습 실행이 살펴본 후보 밤 수 (outcome: LEARNED 또는 그 밤의 skip 사유)")
                .tag(OUTCOME_TAG, outcome)
                .register(registry);
    }
}
