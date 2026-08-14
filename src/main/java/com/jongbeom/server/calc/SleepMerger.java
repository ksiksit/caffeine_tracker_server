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

    /** 병합 전 원시 수면 샘플. {@code hkValue}는 HealthKit HKCategoryValueSleepAnalysis 원값. */
    public record Sample(Instant start, Instant end, int hkValue) {
    }

    /** 병합 후 타임라인 구간 — 겹침 해소가 끝난 출력. */
    public record Interval(Instant start, Instant end, int hkValue) {
    }

    /**
     * 수면 샘플을 병합해 시간순 구간 리스트로 만든다.
     * <ul>
     *   <li>입력은 정렬돼 있지 않아도 된다 — 경계점 기준으로 재구성한다.</li>
     *   <li>어떤 샘플도 덮지 않는 빈 구간은 결과에서 빠진다(간극 보존, 메움 없음).</li>
     *   <li>같은 단계가 정확히 맞닿은 구간만 이어붙인다.</li>
     *   <li>결과는 시작 시각 오름차순.</li>
     * </ul>
     */
    public static List<Interval> merge(List<Sample> samples) {
        if (samples.isEmpty()) {
            return List.of();
        }
        List<Instant> boundaries = collectBoundaries(samples);
        List<Interval> intervals = resolveSegments(boundaries, samples);
        return coalesceAdjacentSameStage(intervals);
    }

    /** 1. 모든 샘플의 시작·끝 시각을 모아 정렬한 경계점 목록. */
    private static List<Instant> collectBoundaries(List<Sample> samples) {
        TreeSet<Instant> boundarySet = new TreeSet<>();
        for (Sample sample : samples) {
            boundarySet.add(sample.start());
            boundarySet.add(sample.end());
        }
        return new ArrayList<>(boundarySet);
    }

    /** 2. 이웃 경계점 쌍(세그먼트)마다 세그먼트를 완전히 덮는 샘플 중 최고 우선순위 단계를 고른다. */
    private static List<Interval> resolveSegments(List<Instant> boundaries, List<Sample> samples) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            Instant segmentStart = boundaries.get(i);
            Instant segmentEnd = boundaries.get(i + 1);
            Integer stageValue = highestPriorityStageCovering(samples, segmentStart, segmentEnd);
            if (stageValue == null) {
                continue;
            }
            intervals.add(new Interval(segmentStart, segmentEnd, stageValue));
        }
        return intervals;
    }

    /**
     * 세그먼트를 완전히 덮는 샘플 중 최고 우선순위의 hkValue. 없으면 null.
     * 우선순위 동률이면 입력에서 먼저 온 샘플이 이긴다(strict {@code >}) — iOS와 동일한 타이브레이크.
     */
    private static Integer highestPriorityStageCovering(List<Sample> samples, Instant segmentStart, Instant segmentEnd) {
        Integer bestValue = null;
        int bestPriority = Integer.MIN_VALUE;
        for (Sample sample : samples) {
            if (!covers(sample, segmentStart, segmentEnd)) {
                continue;
            }
            int priority = SleepStage.stagePriority(sample.hkValue());
            if (priority > bestPriority) {
                bestPriority = priority;
                bestValue = sample.hkValue();
            }
        }
        return bestValue;
    }

    /** sample이 세그먼트를 완전히 포함하는가 (sample.start <= segmentStart && sample.end >= segmentEnd). */
    private static boolean covers(Sample sample, Instant segmentStart, Instant segmentEnd) {
        return !sample.start().isAfter(segmentStart) && !sample.end().isBefore(segmentEnd);
    }

    /** 3. 정확히 맞닿은(간극 0) 같은 단계 구간을 하나로 이어붙인다. */
    private static List<Interval> coalesceAdjacentSameStage(List<Interval> intervals) {
        List<Interval> merged = new ArrayList<>();
        for (Interval current : intervals) {
            if (!merged.isEmpty()) {
                Interval previous = merged.get(merged.size() - 1);
                if (previous.hkValue() == current.hkValue() && previous.end().equals(current.start())) {
                    merged.set(merged.size() - 1, new Interval(previous.start(), current.end(), previous.hkValue()));
                    continue;
                }
            }
            merged.add(current);
        }
        return merged;
    }
}
