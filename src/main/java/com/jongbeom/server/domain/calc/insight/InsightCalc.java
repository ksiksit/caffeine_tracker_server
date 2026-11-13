package com.jongbeom.server.domain.calc.insight;

import com.jongbeom.server.domain.calc.pk.Pharmacokinetics;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.Dose;
import com.jongbeom.server.domain.calc.sleep.SleepStage;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc.StageDuration;
import com.jongbeom.server.domain.calc.time.LocalCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * 인사이트(기간별 섭취 통계·카페인-수면 상관 리포트) 순수 계산. 숫자만 내고 문장은 만들지 않는다.
 * 기준값은 docs/insight-plan.md §7 ★1~★5 확정안(2026-11-13)이다.
 *
 * <p>기존 calc 상수(50mg 안전선, 400mg 권장량, 24h 룩백, 05:00 하루 경계)는 읽기만 한다.
 */
public final class InsightCalc {

    private InsightCalc() {
    }

    /** 리포트를 보여 줄 최소 밤 수(★3). */
    public static final int MIN_REPORT_NIGHTS = 5;
    /** 비교 그룹당 최소 밤 수(★3). 학습 대시보드 R² 최소 관측 수(LearningStatsCalc)와 값만 맞춘다. */
    public static final int MIN_GROUP_NIGHTS = 3;
    /** SOL 비교에 필요한 그룹당 SOL 있는 밤 수(★3). */
    public static final int MIN_SOL_NIGHTS = 3;
    /** "차이 있음" SOL 기준(분, ★2). 학습 모델 BETA 0.10분/mg × 50mg. */
    public static final double SOL_DIFF_THRESHOLD_MINUTES = 5.0;
    /** "차이 있음" 수면 효율 기준(0~1 스케일, ★2). 3%p. */
    public static final double EFFICIENCY_DIFF_THRESHOLD = 0.03;
    /** 비교 A 경계 — 홈 취침 안전선과 같은 상수. */
    public static final double RESIDUAL_THRESHOLD_MG = Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG;

    private static final Duration DOSES_LOOKBACK = Duration.ofHours(24);
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int DAY_START_MINUTE = LocalCalendar.CHART_START_HOUR * 60;

    // ---------------------------------------------------------------- 날짜 경계

    /** 섭취일 date 의 시작 = 로컬 date {@value LocalCalendar#CHART_START_HOUR}:00 ({@link LocalCalendar#chartStart}와 같은 방식). */
    public static Instant intakeDayStart(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).plusHours(LocalCalendar.CHART_START_HOUR).toInstant();
    }

    /** time 이 속한 섭취일 — {@link LocalCalendar#intakeDayOf}에 위임(홈의 "오늘"과 같은 정의). */
    public static LocalDate intakeDayOf(Instant time, ZoneId zone) {
        return LocalCalendar.intakeDayOf(time, zone);
    }

    // ---------------------------------------------------------------- 밤 단위

    /** 한 밤의 수면 지표(분 단위). */
    public record SleepMetrics(Double solMinutes, double sleepEfficiency, double totalSleepMinutes, Double deepSleepRatio) {
    }

    /**
     * 수면 요약 → 리포트 지표. SOL 은 초 ÷ 60(null 유지). 깊은 수면 비율은 deep ÷ 총 수면이며
     * 단계 구분 데이터(core·deep·rem)가 없거나 총 수면이 0이면 null.
     */
    public static SleepMetrics sleepMetrics(SleepSummaryCalc.Result summary) {
        Double sol = summary.sleepOnsetLatencySeconds() == null
                ? null : summary.sleepOnsetLatencySeconds() / SECONDS_PER_MINUTE;
        return new SleepMetrics(sol, summary.sleepEfficiency(),
                summary.totalSleepSeconds() / SECONDS_PER_MINUTE, deepSleepRatio(summary));
    }

    private static Double deepSleepRatio(SleepSummaryCalc.Result summary) {
        boolean hasStages = summary.stageBreakdown().stream().map(StageDuration::stage)
                .anyMatch(s -> s == SleepStage.ASLEEP_CORE || s == SleepStage.ASLEEP_DEEP || s == SleepStage.ASLEEP_REM);
        if (!hasStages || summary.totalSleepSeconds() <= 0) {
            return null;
        }
        double deep = summary.stageBreakdown().stream()
                .filter(s -> s.stage() == SleepStage.ASLEEP_DEEP)
                .mapToDouble(StageDuration::durationSeconds).sum();
        return deep / summary.totalSleepSeconds();
    }

    /** 한 밤의 섭취 쪽 값. lastIntakeAt·cutoffKept 는 짝지은 섭취일 기록이 없으면 null. */
    public record NightIntake(double residualAtBedtimeMg, double dayTotalMg, Instant lastIntakeAt, Boolean cutoffKept) {
    }

    /**
     * 아침 날짜 {@code date}의 밤에 대한 섭취 쪽 계산.
     * <ul>
     *   <li>잔량: 취침 직전 24h(양끝 포함, 학습 룩백과 같다) 기록으로 {@link Pharmacokinetics#totalRemaining}. 기록 없으면 0.</li>
     *   <li>짝지은 섭취일(★5): date−1 05:00 ~ date 05:00 중 취침 시각 이전(포함) 기록.</li>
     *   <li>마감 판정(★4): {@link #cutoffKept}. 그 섭취일 기록이 없으면 null.</li>
     * </ul>
     *
     * @param doses 시각 오름차순. 필요한 범위(취침 −24h ~ 취침)를 덮기만 하면 범위 밖 기록이 섞여도 된다
     */
    public static NightIntake nightIntake(LocalDate date, Instant bedtime, List<Dose> doses, ZoneId zone,
                                          double halfLifeHours, double referenceDoseMg) {
        Instant lookbackStart = bedtime.minus(DOSES_LOOKBACK);
        List<Dose> lookback = doses.stream()
                .filter(d -> !d.timestamp().isBefore(lookbackStart) && !d.timestamp().isAfter(bedtime))
                .toList();
        double residual = lookback.isEmpty() ? 0 : Pharmacokinetics.totalRemaining(bedtime, lookback, halfLifeHours);

        Instant dayStart = intakeDayStart(date.minusDays(1), zone);
        Instant dayEnd = intakeDayStart(date, zone);
        List<Dose> paired = doses.stream()
                .filter(d -> !d.timestamp().isBefore(dayStart) && d.timestamp().isBefore(dayEnd)
                        && !d.timestamp().isAfter(bedtime))
                .toList();
        if (paired.isEmpty()) {
            return new NightIntake(residual, 0, null, null);
        }
        double dayTotal = paired.stream().mapToDouble(Dose::amountMg).sum();
        Instant last = paired.get(paired.size() - 1).timestamp();
        List<Dose> prior = lookback.stream().filter(d -> d.timestamp().isBefore(last)).toList();
        return new NightIntake(residual, dayTotal, last,
                cutoffKept(prior, last, bedtime, referenceDoseMg, halfLifeHours));
    }

    /**
     * 비교 B 판정: 마지막 잔 이전 기록으로 홈과 같은 {@link Pharmacokinetics#cutoffTime}을 계산해
     * SAFE_ANYTIME → 지킴, CUTOFF → 마지막 잔 ≤ 마감이면 지킴, ALREADY_EXCEEDED → 넘김.
     */
    public static boolean cutoffKept(List<Dose> priorDoses, Instant lastIntakeAt, Instant bedtime,
                                     double referenceDoseMg, double halfLifeHours) {
        CutoffResult cutoff = Pharmacokinetics.cutoffTime(
                priorDoses, bedtime, referenceDoseMg, halfLifeHours, Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG);
        return switch (cutoff.status()) {
            case SAFE_ANYTIME -> true;
            case CUTOFF -> !lastIntakeAt.isAfter(cutoff.cutoff());
            case ALREADY_EXCEEDED -> false;
        };
    }

    // ---------------------------------------------------------------- 비교

    public static boolean reportSufficient(int nightCount) {
        return nightCount >= MIN_REPORT_NIGHTS;
    }

    /** 리포트 최소 밤 수까지 모자란 수(0 이상). */
    public static int moreReportNightsNeeded(int nightCount) {
        return Math.max(0, MIN_REPORT_NIGHTS - nightCount);
    }

    /** 그룹 지표. 평균은 값이 있는 밤만으로 내고, 그런 밤이 없으면 null. */
    public record GroupStats(int nightCount, int solNightCount, Double avgSolMinutes, Double avgSleepEfficiency,
                             Double avgTotalSleepMinutes, Double avgDeepSleepRatio) {
    }

    public static GroupStats group(List<SleepMetrics> nights) {
        int solNights = (int) nights.stream().filter(n -> n.solMinutes() != null).count();
        return new GroupStats(nights.size(), solNights,
                average(nights, SleepMetrics::solMinutes),
                average(nights, SleepMetrics::sleepEfficiency),
                average(nights, SleepMetrics::totalSleepMinutes),
                average(nights, SleepMetrics::deepSleepRatio));
    }

    private static Double average(List<SleepMetrics> nights, Function<SleepMetrics, Double> field) {
        OptionalDouble avg = nights.stream().map(field).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        return avg.isPresent() ? avg.getAsDouble() : null;
    }

    /**
     * 비교 결과. sufficient 가 false 면 moreNightsNeeded 가 채워지고 차이는 null, hasDifference 는 false.
     * solDiffMinutes 는 두 그룹 SOL 있는 밤이 각각 {@value #MIN_SOL_NIGHTS}밤 이상일 때만.
     */
    public record ComparisonResult(GroupStats high, GroupStats low, boolean sufficient, Integer moreNightsNeeded,
                                   Double solDiffMinutes, Double efficiencyDiff,
                                   boolean solDifferent, boolean efficiencyDifferent) {
        public boolean hasDifference() {
            return solDifferent || efficiencyDifferent;
        }
    }

    /**
     * 두 그룹 비교(차이 = high − low). 리포트가 충분하고 두 그룹이 각각 {@value #MIN_GROUP_NIGHTS}밤 이상이어야 sufficient.
     * 모자란 밤 수는 두 그룹의 max(0, 3 − 밤 수) 합. "차이 있음"은 |SOL 차이| ≥ 5분 또는 |효율 차이| ≥ 0.03.
     */
    public static ComparisonResult compare(List<SleepMetrics> highNights, List<SleepMetrics> lowNights,
                                           boolean reportSufficient) {
        GroupStats high = group(highNights);
        GroupStats low = group(lowNights);
        int shortage = Math.max(0, MIN_GROUP_NIGHTS - high.nightCount()) + Math.max(0, MIN_GROUP_NIGHTS - low.nightCount());
        boolean sufficient = reportSufficient && shortage == 0;
        if (!sufficient) {
            // 리포트가 부족(5밤 미만)하면 두 그룹 합도 5 미만이라 shortage ≥ 1 이다(3 + 3 > 5)
            return new ComparisonResult(high, low, false, shortage, null, null, false, false);
        }
        Double solDiff = high.solNightCount() >= MIN_SOL_NIGHTS && low.solNightCount() >= MIN_SOL_NIGHTS
                ? high.avgSolMinutes() - low.avgSolMinutes() : null;
        double efficiencyDiff = high.avgSleepEfficiency() - low.avgSleepEfficiency();
        boolean solDifferent = solDiff != null && Math.abs(solDiff) >= SOL_DIFF_THRESHOLD_MINUTES;
        boolean efficiencyDifferent = Math.abs(efficiencyDiff) >= EFFICIENCY_DIFF_THRESHOLD;
        return new ComparisonResult(high, low, true, null, solDiff, efficiencyDiff, solDifferent, efficiencyDifferent);
    }

    // ---------------------------------------------------------------- 섭취 통계

    /** 섭취일 하루 집계. first/last 는 기록이 없으면 null. */
    public record DayIntake(LocalDate date, double totalMg, int recordCount, Instant firstIntakeAt,
                            Instant lastIntakeAt, boolean overLimit) {
    }

    /** @param dosesOfDay 그 섭취일(05:00 경계) 기록, 시각 오름차순 */
    public static DayIntake dayIntake(LocalDate date, List<Dose> dosesOfDay) {
        double total = dosesOfDay.stream().mapToDouble(Dose::amountMg).sum();
        Instant first = dosesOfDay.isEmpty() ? null : dosesOfDay.get(0).timestamp();
        Instant last = dosesOfDay.isEmpty() ? null : dosesOfDay.get(dosesOfDay.size() - 1).timestamp();
        return new DayIntake(date, total, dosesOfDay.size(), first, last,
                total > Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG);
    }

    /** 기간 요약. 평균·최대·시각 평균은 그런 날이 없으면 null. */
    public record IntakeSummary(int recordedDayCount, Double averageDailyMg, Double maxDailyMg,
                                int overLimitDayCount, Integer averageLastIntakeMinuteOfDay) {
    }

    /**
     * 기록 있는 날 수는 전체(오늘 포함), 나머지 요약은 기록 있는 날 중 끝난 날({@code intakeToday} 이전)만으로 낸다.
     * {@code intakeToday}는 섭취일 기준 오늘({@link #intakeDayOf}) — 00:00~05:00 에는 달력 전날이 아직 끝나지 않은 날이다.
     * 마지막 섭취 시각 평균은 05:00 기준 경과 분(벽시계)으로 평균한 뒤 벽시계 분(0~1439)으로 되돌린다.
     */
    public static IntakeSummary summarize(List<DayIntake> days, LocalDate intakeToday, ZoneId zone) {
        int recorded = (int) days.stream().filter(d -> d.recordCount() > 0).count();
        List<DayIntake> complete = days.stream()
                .filter(d -> d.recordCount() > 0 && d.date().isBefore(intakeToday))
                .toList();
        if (complete.isEmpty()) {
            return new IntakeSummary(recorded, null, null, 0, null);
        }
        double avg = complete.stream().mapToDouble(DayIntake::totalMg).average().orElseThrow();
        double max = complete.stream().mapToDouble(DayIntake::totalMg).max().orElseThrow();
        int overLimit = (int) complete.stream().filter(DayIntake::overLimit).count();
        double avgOffset = complete.stream()
                .mapToDouble(d -> minutesSinceDayStart(d.lastIntakeAt(), zone))
                .average().orElseThrow();
        int minuteOfDay = (int) (Math.round(avgOffset + DAY_START_MINUTE) % MINUTES_PER_DAY);
        return new IntakeSummary(recorded, avg, max, overLimit, minuteOfDay);
    }

    /** 벽시계 기준 05:00 이후 경과 분(0 이상 1440 미만). 00:00~05:00 은 전날 05:00 기준이라 1140 이상이 된다. */
    static double minutesSinceDayStart(Instant time, ZoneId zone) {
        double wallMinutes = time.atZone(zone).toLocalTime().toSecondOfDay() / SECONDS_PER_MINUTE;
        return (wallMinutes - DAY_START_MINUTE + MINUTES_PER_DAY) % MINUTES_PER_DAY;
    }
}
