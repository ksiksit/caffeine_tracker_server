package com.jongbeom.server.domain.sleep;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 수면 샘플 업로드 메트릭. 업로드는 중복이 섞여도 200 이라 HTTP 메트릭으로는 실제로 몇 개가 저장됐는지 보이지 않는다.
 *
 * <ul>
 *   <li>{@code caffeine.sleep.samples{result=inserted}} — 새로 저장한 샘플 수(응답 {@code inserted}).</li>
 *   <li>{@code caffeine.sleep.samples{result=duplicate}} — 받았지만 저장하지 않은 샘플 수(응답 {@code received - inserted}).
 *       이미 저장된 {@code clientUuid}와 같은 배치 안의 중복을 합친 값. 이 비율이 높으면 앱이 같은 샘플을 반복해 올리고 있다.</li>
 * </ul>
 *
 * 사용자 식별자는 태그로 붙이지 않는다. 메트릭 이름·태그를 바꾸면 Grafana 대시보드 JSON 과 운영 가이드도 같이 바꾼다.
 */
@Component
public class SleepMetrics {

    public static final String SAMPLES = "caffeine.sleep.samples";
    public static final String RESULT_TAG = "result";
    public static final String INSERTED = "inserted";
    public static final String DUPLICATE = "duplicate";

    private final Counter inserted;
    private final Counter duplicate;

    public SleepMetrics(MeterRegistry registry) {
        inserted = counter(registry, INSERTED);
        duplicate = counter(registry, DUPLICATE);
    }

    /** 업로드 1건. {@code received}는 요청 샘플 수, {@code insertedCount}는 새로 저장한 수. */
    public void recordUpload(int received, int insertedCount) {
        inserted.increment(insertedCount);
        duplicate.increment(received - insertedCount);
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder(SAMPLES)
                .description("업로드된 수면 샘플 수 (result: inserted 새로 저장 / duplicate 이미 있거나 배치 내 중복)")
                .tag(RESULT_TAG, result)
                .register(registry);
    }
}
