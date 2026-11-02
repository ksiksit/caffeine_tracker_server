package com.jongbeom.server.domain.calc.sleep;

import com.jongbeom.server.domain.calc.sleep.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.time.Elapsed;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 병합된 수면 구간으로부터 요약 지표 계산. iOS {@code SleepSummary} 파생 프로퍼티 이관.
 * 총수면/침대시간/효율/수면잠복기(SOL)/단계별 시간.
 */
public final class SleepSummaryCalc {

    private SleepSummaryCalc() {
    }

    public record StageDuration(SleepStage stage, double durationSeconds) {
    }

    public record Result(
            double totalSleepSeconds,
            double timeInBedSeconds,
            double sleepEfficiency,
            Double sleepOnsetLatencySeconds, // null 가능
            List<StageDuration> stageBreakdown
    ) {
    }

    /**
     * 병합 구간으로부터 수면 요약을 계산한다. 모든 시간 값의 단위는 초.
     *
     * <ul>
     *   <li>{@code timeInBedSeconds} — 병합 구간 전체 스팬(최초 시작~최후 끝). IN_BED 단계 합이 아님에 주의.</li>
     *   <li>{@code sleepEfficiency} — 총수면/침대시간, 0~1 비율(퍼센트 아님). 침대시간 0이면 0.</li>
     *   <li>{@code sleepOnsetLatencySeconds} — 초 단위, 계산 불가·음수면 null.
     *       (학습용 {@code BedtimeExtractor.Result#solMinutes()}는 분 단위 — 두 SOL API의 단위가 다름)</li>
     *   <li>{@code stageBreakdown} — 지속시간 > 0인 단계만, {@link SleepStage} 선언 순서(iOS allCases 순서).</li>
     * </ul>
     *
     * @param intervals    병합된 수면 구간
     * @param bedtimeStart 원시 샘플의 최초 inBed 시작(없으면 null → 병합 inBed 로 폴백)
     */
    public static Result compute(List<Interval> intervals, Instant bedtimeStart) {
        SleepScan scan = SleepScan.of(intervals);
        Instant earliest = null;
        Instant latest = null;
        Map<SleepStage, Double> breakdown = new EnumMap<>(SleepStage.class);

        for (Interval interval : intervals) {
            SleepStage stage = SleepStage.fromHkValue(interval.hkValue());
            breakdown.merge(stage, Elapsed.seconds(interval.start(), interval.end()), Double::sum);

            if (earliest == null || interval.start().isBefore(earliest)) {
                earliest = interval.start();
            }
            if (latest == null || interval.end().isAfter(latest)) {
                latest = interval.end();
            }
        }

        double totalSleep = scan.totalSleepSeconds();
        double timeInBed = (earliest != null && latest != null) ? Elapsed.seconds(earliest, latest) : 0;
        double efficiency = timeInBed > 0 ? totalSleep / timeInBed : 0;

        // SOL 기준점: bedtimeStart(원시) ?? 병합 inBed 시작
        Instant inBedStart = bedtimeStart != null ? bedtimeStart : scan.earliestInBed();
        Double sleepOnsetLatency = computeSleepOnsetLatency(inBedStart, scan.firstAsleep());

        return new Result(totalSleep, timeInBed, efficiency, sleepOnsetLatency, orderedStageBreakdown(breakdown));
    }

    /** SOL = inBed 시작 → 최초 asleep 시작까지 초. 어느 한쪽이 없거나 음수면 null. */
    private static Double computeSleepOnsetLatency(Instant inBedStart, Instant firstAsleep) {
        if (inBedStart == null || firstAsleep == null) {
            return null;
        }
        double latencySeconds = Elapsed.seconds(inBedStart, firstAsleep);
        return latencySeconds >= 0 ? latencySeconds : null;
    }

    /** 지속시간 > 0인 단계만, 선언 순서(iOS allCases 순서)대로. */
    private static List<StageDuration> orderedStageBreakdown(Map<SleepStage, Double> breakdown) {
        List<StageDuration> stageBreakdown = new ArrayList<>();
        for (SleepStage stage : SleepStage.values()) {
            Double durationSeconds = breakdown.get(stage);
            if (durationSeconds != null && durationSeconds > 0) {
                stageBreakdown.add(new StageDuration(stage, durationSeconds));
            }
        }
        return stageBreakdown;
    }
}
