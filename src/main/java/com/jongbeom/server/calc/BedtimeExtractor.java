package com.jongbeom.server.calc;

import com.jongbeom.server.calc.SleepMerger.Interval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 병합된 수면 구간 + 설정에서 학습용 bedtime과 SOL(분)을 추출하고 게이트를 적용.
 * iOS {@code BayesianHalfLifeLearner.extractBedtime/bedtimeStart/configuredBedtime} 이관.
 *
 * <p>주의: 여기서의 SOL은 {@link SleepSummaryCalc}의 요약 SOL과 별개다 —
 * 취침시각이 설정값으로 폴백될 수 있어 학습 게이트 전용 로직이 필요하다.
 */
public final class BedtimeExtractor {

    private BedtimeExtractor() {
    }

    /** 취침 시각 윈도우 경계(18시) — bedtimeHour가 이 이상이면 전날로 본다. */
    private static final int WINDOW_START_HOUR = LocalCalendar.SLEEP_WINDOW_START_HOUR;

    public enum Gate {SLEEP_TOO_SHORT, MISSING_BEDTIME, INVALID_SOL}

    /** failure == null 이면 성공(bedtime/solMinutes 유효). */
    public record Result(Instant bedtime, double solMinutes, Gate failure) {
        static Result ok(Instant bedtime, double solMinutes) {
            return new Result(bedtime, solMinutes, null);
        }

        static Result fail(Gate gate) {
            return new Result(null, 0, gate);
        }

        public boolean isOk() {
            return failure == null;
        }
    }

    private static double seconds(Instant a, Instant b) {
        return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0;
    }

    public static Result extract(
            List<Interval> intervals, Instant bedtimeStart,
            int bedtimeHour, int bedtimeMinute, LocalDate summaryDate, ZoneId zone) {

        double totalSleep = 0;
        Instant firstAsleep = null;
        Instant earliestInBed = null;
        for (Interval iv : intervals) {
            SleepStage stage = SleepStage.fromHkValue(iv.hkValue());
            if (stage.isAsleep()) {
                totalSleep += seconds(iv.start(), iv.end());
                if (firstAsleep == null || iv.start().isBefore(firstAsleep)) {
                    firstAsleep = iv.start();
                }
            }
            if (stage == SleepStage.IN_BED
                    && (earliestInBed == null || iv.start().isBefore(earliestInBed))) {
                earliestInBed = iv.start();
            }
        }

        if (totalSleep < BayesianHalfLifeUpdater.MIN_SLEEP_DURATION_SECONDS) {
            return Result.fail(Gate.SLEEP_TOO_SHORT);
        }
        if (firstAsleep == null) {
            return Result.fail(Gate.MISSING_BEDTIME);
        }

        Instant bedtime = resolveBedtime(bedtimeStart, earliestInBed, firstAsleep,
                bedtimeHour, bedtimeMinute, summaryDate, zone);
        if (bedtime == null) {
            return Result.fail(Gate.MISSING_BEDTIME);
        }

        double solSeconds = seconds(bedtime, firstAsleep);
        if (!Double.isFinite(solSeconds) || solSeconds < 0) {
            return Result.fail(Gate.INVALID_SOL);
        }
        double solMinutes = solSeconds / 60.0;
        if (solMinutes > BayesianHalfLifeUpdater.MAX_ACCEPTABLE_SOL_MINUTES) {
            return Result.fail(Gate.INVALID_SOL);
        }
        return Result.ok(bedtime, solMinutes);
    }

    /** recordedBedtime(원시 inBed ?? 병합 inBed) ?? 설정 취침시각 폴백. */
    private static Instant resolveBedtime(
            Instant bedtimeStart, Instant earliestInBed, Instant firstAsleep,
            int bedtimeHour, int bedtimeMinute, LocalDate summaryDate, ZoneId zone) {
        Instant recorded = bedtimeStart != null ? bedtimeStart : earliestInBed;
        if (recorded != null) {
            return recorded;
        }
        return configuredBedtime(summaryDate, firstAsleep, bedtimeHour, bedtimeMinute, zone);
    }

    /** 설정 취침시각으로 bedtime 추정. bedtimeHour>=18이면 전날. SOL 범위 벗어나면 null. */
    private static Instant configuredBedtime(
            LocalDate summaryDate, Instant firstAsleep, int bedtimeHour, int bedtimeMinute, ZoneId zone) {
        LocalDate baseDate = bedtimeHour >= WINDOW_START_HOUR ? summaryDate.minusDays(1) : summaryDate;
        ZonedDateTime bedtimeZ = baseDate.atStartOfDay(zone).withHour(bedtimeHour).withMinute(bedtimeMinute);
        Instant bedtime = bedtimeZ.toInstant();
        double solSeconds = seconds(bedtime, firstAsleep);
        if (!Double.isFinite(solSeconds) || solSeconds < 0
                || solSeconds / 60.0 > BayesianHalfLifeUpdater.MAX_ACCEPTABLE_SOL_MINUTES) {
            return null;
        }
        return bedtime;
    }
}
