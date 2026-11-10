package com.jongbeom.server.domain.caffeine;

import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.CutoffResult;
import java.time.Instant;

/**
 * 다른 도메인이 읽는 오늘 현황 요약. HTTP 응답({@code CaffeineTodayResponse})과 분리해 둔다 —
 * 응답 DTO 는 API 문서와 대조되는 계약이라, 내부 소비자가 그 모양에 묶이면 한쪽 사정으로 다른 쪽이 바뀐다.
 * 값의 정의는 {@code CaffeineService.today}와 같다.
 */
public record CaffeineStatus(
        Instant bedtime,
        int todayTotal,
        boolean overDailyLimit,
        double predictedAtBedtime,
        CutoffResult cutoff,
        int referenceDoseMg
) {
}
