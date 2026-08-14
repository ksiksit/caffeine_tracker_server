package com.jongbeom.server.calc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 카페인 약동학 계산 (지수 감쇠 모델).
 *
 * <p>iOS {@code CaffeineKineticsCalculator.swift} + {@code CaffeineHelpers.caffeineCutoffTime} 포팅.
 * 모든 메서드는 순수함수이며 절대시각({@link Instant}) 기준으로 계산한다. "하루 경계 / 취침시각" 같은
 * 로컬 달력 연산은 호출 측({@link com.jongbeom.server.calc.LocalCalendar})에서 타임존으로 변환해 전달한다.
 */
public final class Pharmacokinetics {

    private Pharmacokinetics() {
    }

    // iOS AppConstants 와 1:1. 임의 변경 금지(도메인 근거값).
    public static final double DEFAULT_HALF_LIFE_HOURS = 5.0;
    public static final double MIN_HALF_LIFE_HOURS = 3.0;
    public static final double MAX_HALF_LIFE_HOURS = 7.0;
    public static final int CHART_INTERVAL_MINUTES = 15;
    public static final int CHART_START_HOUR = 5;
    public static final int CHART_FORWARD_HOURS = 24;
    public static final double BEDTIME_SAFE_THRESHOLD_MG = 50.0;
    public static final int DAILY_RECOMMENDED_LIMIT_MG = 400;

    // 단위 상수. LN_2는 리터럴로 바꾸지 말 것 — Math.log(2) 결과를 그대로 써야 iOS 골든과 비트 일치.
    private static final double LN_2 = Math.log(2);
    private static final double MILLIS_PER_HOUR = 3_600_000.0;
    private static final long SECONDS_PER_MINUTE = 60;
    /** dose 직전 점의 간격(1초) — 차트에서 섭취 시점이 수직 점프로 보이게 한다. */
    private static final long PRE_DOSE_SAMPLE_OFFSET_SECONDS = 1;

    /** 단일 섭취. */
    public record Dose(Instant timestamp, double amountMg) {
    }

    /** 시계열 한 점. {@code concentrationMg}는 체내 잔량(mg). */
    public record DataPoint(int index, Instant time, double concentrationMg) {
    }

    /**
     * 마감 시각 계산 결과 (← Swift {@code CaffeineCutoffResult} 3-case enum).
     * {@code cutoff}는 CUTOFF 상태에서만, {@code existingAtBedtime}은 ALREADY_EXCEEDED 상태에서만 의미 있다.
     */
    public record CutoffResult(Status status, Instant cutoff, double existingAtBedtime) {
        public enum Status {SAFE_ANYTIME, CUTOFF, ALREADY_EXCEEDED}

        public static CutoffResult safeAnytime() {
            return new CutoffResult(Status.SAFE_ANYTIME, null, 0);
        }

        public static CutoffResult cutoff(Instant at) {
            return new CutoffResult(Status.CUTOFF, at, 0);
        }

        public static CutoffResult alreadyExceeded(double existingAtBedtime) {
            return new CutoffResult(Status.ALREADY_EXCEEDED, null, existingAtBedtime);
        }
    }

    /**
     * dose 가 halfLifeHours 반감기로 hoursSinceDose 경과 후 남은 양(mg).
     * 음수 경과시간·0 이하 용량·0 이하 반감기는 0.
     *
     * @param dose           섭취량 (mg)
     * @param hoursSinceDose 섭취 후 경과 시간 (시간)
     * @param halfLifeHours  반감기 (시간)
     */
    public static double remainingAmount(double dose, double hoursSinceDose, double halfLifeHours) {
        if (hoursSinceDose < 0 || dose <= 0 || halfLifeHours <= 0) {
            return 0;
        }
        double ke = LN_2 / halfLifeHours; // ke: 제거 속도 상수(시간당) = ln2 / t½
        return dose * Math.exp(-ke * hoursSinceDose);
    }

    /**
     * time 시점의 모든 dose 잔량 합(mg, 중첩). 미래 dose 는 음수 경과시간이라 0으로 무시된다.
     * 합산은 doses 리스트 순서대로 — 부동소수점 합산 순서 보존을 위해 루프 유지(스트림 전환 금지).
     */
    public static double totalRemaining(Instant time, List<Dose> doses, double halfLifeHours) {
        double total = 0;
        for (Dose dose : doses) {
            double hours = (time.toEpochMilli() - dose.timestamp().toEpochMilli()) / MILLIS_PER_HOUR;
            total += remainingAmount(dose.amountMg(), hours, halfLifeHours);
        }
        return total;
    }

    /**
     * [start, end] 구간(end 포함)의 잔량 시계열. dose 발생 시점 직전(1초 전)/직후 점을 끼워
     * sharp transition 을 표현한다. (← Swift generateTimeSeries 동일 알고리즘)
     *
     * <ul>
     *   <li>{@code intervalMinutes <= 0}이면 빈 리스트.</li>
     *   <li>직전/직후 점 쌍은 (start, end] 에 엄격히 포함된 dose에만 추가된다.</li>
     *   <li>dose가 틱 정각과 겹치면 같은 타임스탬프 점이 2개 생길 수 있고, 안정 정렬이 삽입 순서를 보존한다.</li>
     *   <li>반환 {@code index}는 정렬 후 0부터 연속 재부여.</li>
     * </ul>
     */
    public static List<DataPoint> generateTimeSeries(
            List<Dose> doses, Instant start, Instant end, int intervalMinutes, double halfLifeHours) {
        if (intervalMinutes <= 0) {
            return List.of();
        }
        long intervalSeconds = (long) intervalMinutes * SECONDS_PER_MINUTE;
        List<DataPoint> points = new ArrayList<>();

        List<Instant> doseTimesInRange = collectDoseTimesInRange(doses, start, end);

        Instant current = start;
        while (!current.isAfter(end)) {
            Instant nextTick = current.plusSeconds(intervalSeconds);
            appendSharpTransitionPoints(points, doseTimesInRange, doses, halfLifeHours, current, nextTick);
            points.add(new DataPoint(0, current, totalRemaining(current, doses, halfLifeHours)));
            current = nextTick;
        }

        return sortAndReindex(points);
    }

    /** (start, end] 에 엄격히 포함되는 dose 시각만 시간순으로. start 정각 dose 는 제외(iOS 동일). */
    private static List<Instant> collectDoseTimesInRange(List<Dose> doses, Instant start, Instant end) {
        return doses.stream()
                .map(Dose::timestamp)
                .filter(t -> t.isAfter(start) && !t.isAfter(end))
                .sorted()
                .toList();
    }

    /**
     * 직전 점(justBefore)이 이번 틱 윈도우 (current, nextTick) 안에 엄격히 들어오는 각 dose에 대해
     * 직전/직후 점 쌍을 추가한다. 각 justBefore는 정확히 한 윈도우에만 속하므로 쌍은 한 번만 생성된다.
     * 틱 점보다 먼저 추가하는 순서는 중복 타임스탬프의 안정 정렬 결과를 결정하므로 바꾸지 말 것.
     */
    private static void appendSharpTransitionPoints(
            List<DataPoint> points, List<Instant> doseTimesInRange, List<Dose> doses,
            double halfLifeHours, Instant current, Instant nextTick) {
        for (Instant doseTime : doseTimesInRange) {
            Instant justBefore = doseTime.minusSeconds(PRE_DOSE_SAMPLE_OFFSET_SECONDS);
            if (current.isBefore(justBefore) && justBefore.isBefore(nextTick)) {
                points.add(new DataPoint(0, justBefore,
                        totalRemaining(justBefore, doses, halfLifeHours)));
                points.add(new DataPoint(0, doseTime,
                        totalRemaining(doseTime, doses, halfLifeHours)));
            }
        }
    }

    /** 시간순 안정 정렬 후 0-기반 연속 index 재부여. 중복 타임스탬프는 삽입 순서 유지(안정 정렬 — 변경 금지). */
    private static List<DataPoint> sortAndReindex(List<DataPoint> points) {
        points.sort(Comparator.comparing(DataPoint::time));
        List<DataPoint> reindexed = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            DataPoint point = points.get(i);
            reindexed.add(new DataPoint(i, point.time(), point.concentrationMg()));
        }
        return reindexed;
    }

    /**
     * referenceDoseMg 만큼을 취침 시 안전선(safeThresholdMg) 이내로 마실 수 있는 가장 늦은 시각.
     * (← Swift caffeineCutoffTime)
     *
     * <ul>
     *   <li>{@code bedtime}은 이미 미래로 해석된 절대시각 — 호출 측이 {@link LocalCalendar#nextBedtime}으로 만든다.</li>
     *   <li>반환된 cutoff 는 과거일 수 있다(이미 마감이 지난 경우).</li>
     *   <li>SAFE_ANYTIME 은 "여유 충분"과 "인자 무효" 두 경우 모두에 쓰인다(iOS 동일).</li>
     * </ul>
     */
    public static CutoffResult cutoffTime(
            List<Dose> currentDoses, Instant bedtime, double referenceDoseMg,
            double halfLifeHours, double safeThresholdMg) {
        if (referenceDoseMg <= 0 || halfLifeHours <= 0) {
            return CutoffResult.safeAnytime();
        }
        double existingAtBedtime = totalRemaining(bedtime, currentDoses, halfLifeHours);
        double headroomAtBedtimeMg = safeThresholdMg - existingAtBedtime;
        if (headroomAtBedtimeMg <= 0) {
            return CutoffResult.alreadyExceeded(existingAtBedtime);
        }
        if (headroomAtBedtimeMg >= referenceDoseMg) {
            return CutoffResult.safeAnytime();
        }
        double hoursBeforeBedtime = hoursToDecay(referenceDoseMg, headroomAtBedtimeMg, halfLifeHours);
        // (long) 캐스트는 0 방향 절단(서브밀리초 버림) — iOS 동일. Math.round/Duration 전환 금지.
        Instant cutoff = bedtime.minusMillis((long) (hoursBeforeBedtime * MILLIS_PER_HOUR));
        return CutoffResult.cutoff(cutoff);
    }

    /** doseMg 가 targetMg 까지 감쇠하는 데 걸리는 시간(시간). dose·e^(-ke·t) = target 을 t 에 대해 푼 값. */
    private static double hoursToDecay(double doseMg, double targetMg, double halfLifeHours) {
        double ke = LN_2 / halfLifeHours;
        return Math.log(doseMg / targetMg) / ke;
    }
}
