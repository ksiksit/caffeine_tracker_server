package com.jongbeom.server.calc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Apple Watch·iPhone 양쪽 수면 샘플을 타임라인 기반으로 병합.
 * iOS {@code SleepDataProcessor.mergeSamples} 동일 알고리즘 포팅.
 * 겹치는 구간은 가장 세분화된(우선순위 높은) 단계 선택, 겹치지 않는 구간은 보존.
 */
public final class SleepMerger {

    private SleepMerger() {
    }

    public record Sample(Instant start, Instant end, int hkValue) {
    }

    public record Interval(Instant start, Instant end, int hkValue) {
    }

    public static List<Interval> merge(List<Sample> samples) {
        if (samples.isEmpty()) {
            return List.of();
        }

        // 1. 모든 경계점 수집·정렬
        TreeSet<Instant> boundarySet = new TreeSet<>();
        for (Sample s : samples) {
            boundarySet.add(s.start());
            boundarySet.add(s.end());
        }
        List<Instant> boundaries = new ArrayList<>(boundarySet);

        // 2. 각 구간을 완전히 포함하는 샘플 중 최고 우선순위 선택
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            Instant start = boundaries.get(i);
            Instant end = boundaries.get(i + 1);

            Integer bestValue = null;
            int bestPriority = Integer.MIN_VALUE;
            for (Sample s : samples) {
                if (!s.start().isAfter(start) && !s.end().isBefore(end)) { // start<=segStart && end>=segEnd
                    int p = SleepStage.stagePriority(s.hkValue());
                    if (p > bestPriority) {
                        bestPriority = p;
                        bestValue = s.hkValue();
                    }
                }
            }
            if (bestValue == null) {
                continue;
            }
            intervals.add(new Interval(start, end, bestValue));
        }

        // 3. 연속된 같은 단계 구간 병합
        List<Interval> merged = new ArrayList<>();
        for (Interval cur : intervals) {
            if (!merged.isEmpty()) {
                Interval last = merged.get(merged.size() - 1);
                if (last.hkValue() == cur.hkValue() && last.end().equals(cur.start())) {
                    merged.set(merged.size() - 1, new Interval(last.start(), cur.end(), last.hkValue()));
                    continue;
                }
            }
            merged.add(cur);
        }

        return merged;
    }
}
