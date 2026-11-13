package com.jongbeom.server.domain.insight.service;

import com.jongbeom.server.domain.caffeine.service.CaffeineService;
import com.jongbeom.server.domain.calc.insight.InsightCalc;
import com.jongbeom.server.domain.calc.insight.InsightCalc.ComparisonResult;
import com.jongbeom.server.domain.calc.insight.InsightCalc.DayIntake;
import com.jongbeom.server.domain.calc.insight.InsightCalc.GroupStats;
import com.jongbeom.server.domain.calc.insight.InsightCalc.IntakeSummary;
import com.jongbeom.server.domain.calc.insight.InsightCalc.NightIntake;
import com.jongbeom.server.domain.calc.insight.InsightCalc.SleepMetrics;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.Dose;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc;
import com.jongbeom.server.domain.calc.time.LocalCalendar;
import com.jongbeom.server.domain.insight.dto.CaffeineSleepReportResponse;
import com.jongbeom.server.domain.insight.dto.CaffeineSleepReportResponse.Comparison;
import com.jongbeom.server.domain.insight.dto.CaffeineSleepReportResponse.Group;
import com.jongbeom.server.domain.insight.dto.CaffeineSleepReportResponse.NightItem;
import com.jongbeom.server.domain.insight.dto.IntakeStatsResponse;
import com.jongbeom.server.domain.insight.dto.IntakeStatsResponse.DayItem;
import com.jongbeom.server.domain.insight.exception.InvalidInsightPeriodException;
import com.jongbeom.server.domain.settings.SettingsSnapshot;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import com.jongbeom.server.domain.sleep.service.SleepService;
import com.jongbeom.server.domain.sleep.service.SleepService.NightData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인사이트: 기간별 섭취 통계 + 카페인·수면 상관 리포트. 요청마다 계산하고 저장하지 않는다.
 * 숫자는 {@link InsightCalc}, 문장은 {@link InsightSentences}. 기준은 docs/insight-plan.md(★1~★5 확정).
 */
@Service
@RequiredArgsConstructor
public class InsightService {

    /** 기간 최대 일수(양 끝 포함). */
    static final int MAX_PERIOD_DAYS = 31;

    private final CaffeineService caffeineService;
    private final SleepService sleepService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public IntakeStatsResponse intakeStats(Long userId, LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate today = validatePeriod(from, to, zone);
        Instant rangeStart = InsightCalc.intakeDayStart(from, zone);
        Instant rangeEnd = InsightCalc.intakeDayStart(to.plusDays(1), zone);
        List<Dose> doses = caffeineService.dosesBetween(userId, rangeStart, rangeEnd);

        List<DayIntake> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant start = InsightCalc.intakeDayStart(date, zone);
            Instant end = InsightCalc.intakeDayStart(date.plusDays(1), zone);
            days.add(InsightCalc.dayIntake(date, doses.stream()
                    .filter(d -> !d.timestamp().isBefore(start) && d.timestamp().isBefore(end))
                    .toList()));
        }
        IntakeSummary summary = InsightCalc.summarize(days, today, zone);
        return new IntakeStatsResponse(
                from, to, days.size(), summary.recordedDayCount(),
                summary.averageDailyMg(), summary.maxDailyMg(), summary.overLimitDayCount(),
                summary.averageLastIntakeMinuteOfDay(),
                InsightSentences.intakeSummary(from, to, today, days.size(), summary),
                days.stream().map(d -> new DayItem(d.date(), d.totalMg(), d.recordCount(),
                        d.firstIntakeAt(), d.lastIntakeAt(), d.overLimit())).toList());
    }

    @Transactional(readOnly = true)
    public CaffeineSleepReportResponse caffeineSleepReport(Long userId, LocalDate from, LocalDate to, ZoneId zone) {
        validatePeriod(from, to, zone);
        // ★1·★4: 요청 시점 유효 반감기·기준 용량 하나로 기간 전체를 계산한다(홈과 같은 값)
        SettingsSnapshot settings = settingsService.snapshot(userId);
        double halfLife = settings.effectiveHalfLifeHours();
        int referenceDoseMg = settings.referenceDoseMg();

        Map<LocalDate, NightData> merged = sleepService.mergedNights(userId, from, to, zone);
        // 한 번에 조회: 첫 밤 취침(≥ 윈도우 시작) −24h 룩백 ~ 마지막 밤 윈도우 끝. 짝지은 섭취일(D−1 05:00 ~ D 05:00)도 이 안이다.
        List<Dose> doses = caffeineService.dosesBetween(userId,
                LocalCalendar.sleepWindowStart(from, zone).minus(Duration.ofHours(24)),
                LocalCalendar.sleepWindowEnd(to, zone));

        List<NightItem> nights = new ArrayList<>();
        List<SleepMetrics> residualHigh = new ArrayList<>();
        List<SleepMetrics> residualLow = new ArrayList<>();
        List<SleepMetrics> cutoffMissed = new ArrayList<>();
        List<SleepMetrics> cutoffKept = new ArrayList<>();
        merged.forEach((date, night) -> {
            if (night.bedtimeStart() == null) {
                return; // 리포트는 설정 취침 시각으로 폴백하지 않는다(§2.2)
            }
            SleepMetrics sleep = InsightCalc.sleepMetrics(
                    SleepSummaryCalc.compute(night.intervals(), night.bedtimeStart()));
            NightIntake intake = InsightCalc.nightIntake(
                    date, night.bedtimeStart(), doses, zone, halfLife, referenceDoseMg);

            (intake.residualAtBedtimeMg() >= InsightCalc.RESIDUAL_THRESHOLD_MG ? residualHigh : residualLow).add(sleep);
            if (intake.cutoffKept() != null) {
                (intake.cutoffKept() ? cutoffKept : cutoffMissed).add(sleep);
            }
            nights.add(new NightItem(date, night.bedtimeStart(), intake.residualAtBedtimeMg(), intake.dayTotalMg(),
                    intake.lastIntakeAt(), intake.cutoffKept(), sleep.solMinutes(), sleep.sleepEfficiency(),
                    sleep.totalSleepMinutes(), sleep.deepSleepRatio()));
        });

        int nightCount = nights.size();
        boolean sufficient = InsightCalc.reportSufficient(nightCount);
        ComparisonResult residual = InsightCalc.compare(residualHigh, residualLow, sufficient);
        ComparisonResult cutoff = InsightCalc.compare(cutoffMissed, cutoffKept, sufficient);
        String residualSentence = InsightSentences.residualComparison(residual);
        String cutoffSentence = InsightSentences.cutoffComparison(cutoff, referenceDoseMg);

        return new CaffeineSleepReportResponse(
                from, to, nightCount, sufficient, InsightCalc.RESIDUAL_THRESHOLD_MG, halfLife,
                toComparison(residual, residualSentence), toComparison(cutoff, cutoffSentence),
                InsightSentences.headlines(nightCount, residual, residualSentence, cutoff, cutoffSentence),
                nights);
    }

    /** from ≤ to, 기간 ≤ 31일, to ≤ 오늘(tz). 통과하면 오늘(로컬 날짜)을 돌려준다. */
    private LocalDate validatePeriod(LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        if (from.isAfter(to)) {
            throw new InvalidInsightPeriodException("from(" + from + ") > to(" + to + ")");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidInsightPeriodException("기간 " + MAX_PERIOD_DAYS + "일 초과: " + from + "~" + to);
        }
        if (to.isAfter(today)) {
            throw new InvalidInsightPeriodException("to(" + to + ") > 오늘(" + today + ")");
        }
        return today;
    }

    private static Comparison toComparison(ComparisonResult result, String sentence) {
        return new Comparison(toGroup(result.high()), toGroup(result.low()), result.sufficient(),
                result.moreNightsNeeded(), result.solDiffMinutes(), result.efficiencyDiff(), sentence);
    }

    private static Group toGroup(GroupStats stats) {
        return new Group(stats.nightCount(), stats.solNightCount(), stats.avgSolMinutes(),
                stats.avgSleepEfficiency(), stats.avgTotalSleepMinutes(), stats.avgDeepSleepRatio());
    }
}
