package com.jongbeom.server.calc;

import com.jongbeom.server.calc.SleepMerger.Interval;
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

    private static double seconds(Instant a, Instant b) {
        return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0;
    }

    /**
     * @param intervals    병합된 수면 구간
     * @param bedtimeStart 원시 샘플의 최초 inBed 시작(없으면 null → 병합 inBed 로 폴백)
     */
    public static Result compute(List<Interval> intervals, Instant bedtimeStart) {
        double totalSleep = 0;
        Instant earliest = null;
        Instant latest = null;
        Instant earliestInBed = null;
        Instant firstAsleep = null;
        Map<SleepStage, Double> breakdown = new EnumMap<>(SleepStage.class);

        for (Interval iv : intervals) {
            SleepStage stage = SleepStage.fromHkValue(iv.hkValue());
            double dur = seconds(iv.start(), iv.end());
            breakdown.merge(stage, dur, Double::sum);

            if (earliest == null || iv.start().isBefore(earliest)) {
                earliest = iv.start();
            }
            if (latest == null || iv.end().isAfter(latest)) {
                latest = iv.end();
            }
            if (stage.isAsleep()) {
                totalSleep += dur;
                if (firstAsleep == null || iv.start().isBefore(firstAsleep)) {
                    firstAsleep = iv.start();
                }
            }
            if (stage == SleepStage.IN_BED
                    && (earliestInBed == null || iv.start().isBefore(earliestInBed))) {
                earliestInBed = iv.start();
            }
        }

        double timeInBed = (earliest != null && latest != null) ? seconds(earliest, latest) : 0;
        double efficiency = timeInBed > 0 ? totalSleep / timeInBed : 0;

        // SOL: bedtimeStart(원시) ?? 병합 inBed 시작 → 최초 asleep 시작. 음수면 null.
        Instant inBedStart = bedtimeStart != null ? bedtimeStart : earliestInBed;
        Double sol = null;
        if (inBedStart != null && firstAsleep != null) {
            double latency = seconds(inBedStart, firstAsleep);
            if (latency >= 0) {
                sol = latency;
            }
        }

        List<StageDuration> stageBreakdown = new ArrayList<>();
        for (SleepStage stage : SleepStage.values()) { // 선언 순서 = iOS allCases 순서
            Double dur = breakdown.get(stage);
            if (dur != null && dur > 0) {
                stageBreakdown.add(new StageDuration(stage, dur));
            }
        }

        return new Result(totalSleep, timeInBed, efficiency, sol, stageBreakdown);
    }
}
