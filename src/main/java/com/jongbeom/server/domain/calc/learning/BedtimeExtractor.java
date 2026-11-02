package com.jongbeom.server.domain.calc.learning;

import com.jongbeom.server.domain.calc.sleep.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.sleep.SleepScan;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc;
import com.jongbeom.server.domain.calc.time.Elapsed;
import com.jongbeom.server.domain.calc.time.LocalCalendar;
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

    /** 학습 게이트: 이보다 짧은 밤은 관측으로 쓰지 않는다(초). */
    public static final long MIN_SLEEP_DURATION_SECONDS = 4 * 3600;
    /** 학습 게이트: SOL 최대 허용치(분). */
    public static final double MAX_ACCEPTABLE_SOL_MINUTES = 180;

    private static final double SECONDS_PER_MINUTE = 60.0;

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

    /**
     * 학습용 bedtime·SOL 추출 + 게이트 적용.
     *
     * @param intervals     병합된 수면 구간 (정렬 불필요)
     * @param bedtimeStart  원시 샘플의 최초 inBed 시작(UTC), 없으면 null
     * @param bedtimeHour   설정 취침 시각(로컬 벽시계, 기록 없을 때 폴백용)
     * @param bedtimeMinute 설정 취침 분
     * @param summaryDate   수면이 끝나는 아침 날짜(로컬)
     * @param zone          클라 IANA 타임존 (§타임존 계약)
     * @return 성공 시 bedtime(UTC)과 solMinutes(분 단위 — {@link SleepSummaryCalc}의 SOL은 초 단위임에 주의),
     *         실패 시 해당 {@link Gate}
     */
    public static Result extract(
            List<Interval> intervals, Instant bedtimeStart,
            int bedtimeHour, int bedtimeMinute, LocalDate summaryDate, ZoneId zone) {

        SleepScan scanned = SleepScan.of(intervals);

        if (scanned.totalSleepSeconds() < MIN_SLEEP_DURATION_SECONDS) {
            return Result.fail(Gate.SLEEP_TOO_SHORT);
        }
        if (scanned.firstAsleep() == null) {
            return Result.fail(Gate.MISSING_BEDTIME);
        }

        Instant bedtime = resolveBedtime(bedtimeStart, scanned.earliestInBed(), scanned.firstAsleep(),
                bedtimeHour, bedtimeMinute, summaryDate, zone);
        if (bedtime == null) {
            return Result.fail(Gate.MISSING_BEDTIME);
        }

        double solSeconds = Elapsed.seconds(bedtime, scanned.firstAsleep());
        if (!isUsableSol(solSeconds)) {
            return Result.fail(Gate.INVALID_SOL);
        }
        return Result.ok(bedtime, solSeconds / SECONDS_PER_MINUTE);
    }

    /** SOL(초)이 학습에 쓸 수 있는 값인가: 유한 · 0 이상 · 최대 허용치(분) 이하. */
    private static boolean isUsableSol(double solSeconds) {
        return Double.isFinite(solSeconds)
                && solSeconds >= 0
                && solSeconds / SECONDS_PER_MINUTE <= MAX_ACCEPTABLE_SOL_MINUTES;
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

    /** 설정 취침시각으로 bedtime 추정. SOL 범위 벗어나면 null. */
    private static Instant configuredBedtime(
            LocalDate summaryDate, Instant firstAsleep, int bedtimeHour, int bedtimeMinute, ZoneId zone) {
        LocalDate nightDate = nightOf(summaryDate, bedtimeHour);
        ZonedDateTime bedtimeZ = nightDate.atStartOfDay(zone).withHour(bedtimeHour).withMinute(bedtimeMinute);
        Instant bedtime = bedtimeZ.toInstant();
        double solSeconds = Elapsed.seconds(bedtime, firstAsleep);
        if (!isUsableSol(solSeconds)) {
            return null;
        }
        return bedtime;
    }

    /**
     * 취침 시각이 속한 밤의 날짜. bedtimeHour ≥ 18이면 summaryDate 전날 저녁으로 본다.
     * 예: summaryDate 4/21(아침), bedtimeHour 23 → 4/20 23:00 로컬.
     */
    private static LocalDate nightOf(LocalDate summaryDate, int bedtimeHour) {
        return bedtimeHour >= WINDOW_START_HOUR ? summaryDate.minusDays(1) : summaryDate;
    }
}
