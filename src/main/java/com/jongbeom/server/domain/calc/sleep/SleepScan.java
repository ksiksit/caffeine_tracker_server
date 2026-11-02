package com.jongbeom.server.domain.calc.sleep;

import com.jongbeom.server.domain.calc.sleep.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.time.Elapsed;
import java.time.Instant;
import java.util.List;

/**
 * 병합 구간 스캔 결과: 총수면(초)·최초 asleep 시작·최초 inBed 시작.
 * {@link SleepSummaryCalc}(요약)와 학습용 bedtime 추출이 같은 기준으로 쓴다.
 *
 * @param totalSleepSeconds asleep 단계 구간 길이 합(초). 입력 순서대로 누적 — 합산 순서 변경 금지.
 * @param firstAsleep       가장 이른 asleep 구간 시작, 없으면 null
 * @param earliestInBed     가장 이른 IN_BED 구간 시작, 없으면 null
 */
public record SleepScan(double totalSleepSeconds, Instant firstAsleep, Instant earliestInBed) {

    /** 구간 리스트를 한 번 훑는다. 정렬 불필요. */
    public static SleepScan of(List<Interval> intervals) {
        double totalSleep = 0;
        Instant firstAsleep = null;
        Instant earliestInBed = null;
        for (Interval interval : intervals) {
            SleepStage stage = SleepStage.fromHkValue(interval.hkValue());
            if (stage.isAsleep()) {
                totalSleep += Elapsed.seconds(interval.start(), interval.end());
                if (firstAsleep == null || interval.start().isBefore(firstAsleep)) {
                    firstAsleep = interval.start();
                }
            }
            if (stage == SleepStage.IN_BED
                    && (earliestInBed == null || interval.start().isBefore(earliestInBed))) {
                earliestInBed = interval.start();
            }
        }
        return new SleepScan(totalSleep, firstAsleep, earliestInBed);
    }
}
