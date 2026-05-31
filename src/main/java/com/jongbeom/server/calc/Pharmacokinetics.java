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

    /** 단일 섭취. */
    public record Dose(Instant timestamp, double amountMg) {
    }

    /** 시계열 한 점. */
    public record DataPoint(int index, Instant time, double concentrationMg) {
    }

    /** 마감 시각 계산 결과 (← Swift {@code CaffeineCutoffResult} 3-case enum). */
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

    /** dose mg 가 halfLife 시간 경과 후 남은 양. 음수 시간/0 용량/0 반감기는 0. */
    public static double remainingAmount(double dose, double hoursSinceDose, double halfLifeHours) {
        if (hoursSinceDose < 0 || dose <= 0 || halfLifeHours <= 0) {
            return 0;
        }
        double ke = Math.log(2) / halfLifeHours;
        return dose * Math.exp(-ke * hoursSinceDose);
    }

    /** time 시점의 모든 dose 잔량 합(중첩). 미래 dose 는 음수 경과시간이라 0으로 무시된다. */
    public static double totalRemaining(Instant time, List<Dose> doses, double halfLifeHours) {
        double total = 0;
        for (Dose dose : doses) {
            double hours = (time.toEpochMilli() - dose.timestamp().toEpochMilli()) / 3_600_000.0;
            total += remainingAmount(dose.amountMg(), hours, halfLifeHours);
        }
        return total;
    }

    /**
     * [start, end] 구간의 잔량 시계열. dose 발생 시점 직전/직후 점을 끼워 sharp transition 을 표현한다.
     * (← Swift generateTimeSeries 동일 알고리즘)
     */
    public static List<DataPoint> generateTimeSeries(
            List<Dose> doses, Instant start, Instant end, int intervalMinutes, double halfLifeHours) {
        if (intervalMinutes <= 0) {
            return List.of();
        }
        long intervalSeconds = (long) intervalMinutes * 60;
        List<DataPoint> points = new ArrayList<>();

        List<Instant> doseTimesInRange = doses.stream()
                .map(Dose::timestamp)
                .filter(t -> t.isAfter(start) && !t.isAfter(end))
                .sorted()
                .toList();

        Instant current = start;
        while (!current.isAfter(end)) {
            for (Instant doseTime : doseTimesInRange) {
                Instant justBefore = doseTime.minusSeconds(1);
                Instant nextTick = current.plusSeconds(intervalSeconds);
                if (current.isBefore(justBefore) && justBefore.isBefore(nextTick)) {
                    points.add(new DataPoint(0, justBefore,
                            totalRemaining(justBefore, doses, halfLifeHours)));
                    points.add(new DataPoint(0, doseTime,
                            totalRemaining(doseTime, doses, halfLifeHours)));
                }
            }
            points.add(new DataPoint(0, current, totalRemaining(current, doses, halfLifeHours)));
            current = current.plusSeconds(intervalSeconds);
        }

        points.sort(Comparator.comparing(DataPoint::time));
        List<DataPoint> reindexed = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            DataPoint p = points.get(i);
            reindexed.add(new DataPoint(i, p.time(), p.concentrationMg()));
        }
        return reindexed;
    }

    /**
     * referenceDoseMg 만큼을 취침 시 안전선(safeThresholdMg) 이내로 마실 수 있는 가장 늦은 시각.
     * (← Swift caffeineCutoffTime)
     */
    public static CutoffResult cutoffTime(
            List<Dose> currentDoses, Instant bedtime, double referenceDoseMg,
            double halfLifeHours, double safeThresholdMg) {
        if (referenceDoseMg <= 0 || halfLifeHours <= 0) {
            return CutoffResult.safeAnytime();
        }
        double existingAtBedtime = totalRemaining(bedtime, currentDoses, halfLifeHours);
        double headroom = safeThresholdMg - existingAtBedtime;
        if (headroom <= 0) {
            return CutoffResult.alreadyExceeded(existingAtBedtime);
        }
        if (headroom >= referenceDoseMg) {
            return CutoffResult.safeAnytime();
        }
        double ke = Math.log(2) / halfLifeHours;
        double hoursBeforeBedtime = Math.log(referenceDoseMg / headroom) / ke;
        Instant cutoff = bedtime.minusMillis((long) (hoursBeforeBedtime * 3_600_000.0));
        return CutoffResult.cutoff(cutoff);
    }
}
