package com.jongbeom.server.domain.calc.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.domain.calc.insight.InsightCalc.ComparisonResult;
import com.jongbeom.server.domain.calc.insight.InsightCalc.DayIntake;
import com.jongbeom.server.domain.calc.insight.InsightCalc.IntakeSummary;
import com.jongbeom.server.domain.calc.insight.InsightCalc.NightIntake;
import com.jongbeom.server.domain.calc.insight.InsightCalc.SleepMetrics;
import com.jongbeom.server.domain.calc.pk.Pharmacokinetics.Dose;
import com.jongbeom.server.domain.calc.sleep.SleepStage;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc.StageDuration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 인사이트 계산 골든값. 기준은 docs/insight-plan.md ★1~★5 확정안, 동작 조건 목록은 docs/features.md 인사이트 행. */
class InsightCalcTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final double HALF_LIFE = 5.0;
    private static final double REFERENCE_DOSE = 75.0;
    /** 2026-06-01 아침에 끝나는 밤, 2026-05-31 23:00 KST 취침. */
    private static final LocalDate NIGHT_DATE = LocalDate.of(2026, 6, 1);
    private static final Instant BEDTIME = kst("2026-05-31T23:00:00");

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }

    private static Dose dose(String local, double mg) {
        return new Dose(kst(local), mg);
    }

    private static SleepMetrics night(Double sol, double efficiency) {
        return new SleepMetrics(sol, efficiency, 420, null);
    }

    private static List<SleepMetrics> nights(int count, Double sol, double efficiency) {
        return Collections.nCopies(count, night(sol, efficiency));
    }

    // ---------------------------------------------------------------- 기준값

    @Test
    void 확정_기준값() {
        assertThat(InsightCalc.MIN_REPORT_NIGHTS).isEqualTo(5);
        assertThat(InsightCalc.MIN_GROUP_NIGHTS).isEqualTo(3);
        assertThat(InsightCalc.MIN_SOL_NIGHTS).isEqualTo(3);
        assertThat(InsightCalc.SOL_DIFF_THRESHOLD_MINUTES).isEqualTo(5.0);
        assertThat(InsightCalc.EFFICIENCY_DIFF_THRESHOLD).isEqualTo(0.03);
        assertThat(InsightCalc.RESIDUAL_THRESHOLD_MG).isEqualTo(50.0);
    }

    @Test
    void 섭취일_시작은_로컬_05시() {
        assertThat(InsightCalc.intakeDayStart(LocalDate.of(2026, 6, 1), SEOUL))
                .isEqualTo(Instant.parse("2026-05-31T20:00:00Z"));
    }

    // ---------------------------------------------------------------- 수면 지표

    @Test
    void 수면지표_SOL은_분_깊은수면비율은_deep_나누기_총수면() {
        SleepSummaryCalc.Result summary = new SleepSummaryCalc.Result(24000, 27000, 24000.0 / 27000, 1800.0, List.of(
                new StageDuration(SleepStage.IN_BED, 1800),
                new StageDuration(SleepStage.ASLEEP_CORE, 18000),
                new StageDuration(SleepStage.ASLEEP_DEEP, 6000)));
        SleepMetrics metrics = InsightCalc.sleepMetrics(summary);
        assertThat(metrics.solMinutes()).isEqualTo(30.0);
        assertThat(metrics.totalSleepMinutes()).isEqualTo(400.0);
        assertThat(metrics.sleepEfficiency()).isCloseTo(0.8889, within(1e-4));
        assertThat(metrics.deepSleepRatio()).isEqualTo(0.25);
    }

    @Test
    void 수면지표_SOL_없으면_null_core만_있으면_깊은수면_0() {
        SleepSummaryCalc.Result summary = new SleepSummaryCalc.Result(18000, 18000, 1.0, null, List.of(
                new StageDuration(SleepStage.ASLEEP_CORE, 18000)));
        SleepMetrics metrics = InsightCalc.sleepMetrics(summary);
        assertThat(metrics.solMinutes()).isNull();
        assertThat(metrics.deepSleepRatio()).isEqualTo(0.0);
    }

    @Test
    void 깊은수면비율_단계구분_없거나_총수면_0이면_null() {
        SleepSummaryCalc.Result unspecifiedOnly = new SleepSummaryCalc.Result(18000, 18000, 1.0, null, List.of(
                new StageDuration(SleepStage.ASLEEP_UNSPECIFIED, 18000)));
        SleepSummaryCalc.Result inBedOnly = new SleepSummaryCalc.Result(0, 3600, 0, null, List.of(
                new StageDuration(SleepStage.IN_BED, 3600)));
        assertThat(InsightCalc.sleepMetrics(unspecifiedOnly).deepSleepRatio()).isNull();
        assertThat(InsightCalc.sleepMetrics(inBedOnly).deepSleepRatio()).isNull();
    }

    // ---------------------------------------------------------------- 밤 섭취

    @Test
    void 취침잔량_24시간_룩백_골든값() {
        // 200mg, 취침 4h 전, 반감기 5h → 200·2^(-0.8) = 114.8698…
        NightIntake intake = InsightCalc.nightIntake(NIGHT_DATE, BEDTIME,
                List.of(dose("2026-05-31T19:00:00", 200)), SEOUL, HALF_LIFE, REFERENCE_DOSE);
        assertThat(intake.residualAtBedtimeMg()).isCloseTo(114.8698, within(1e-4));
        assertThat(intake.dayTotalMg()).isEqualTo(200.0);
        assertThat(intake.lastIntakeAt()).isEqualTo(kst("2026-05-31T19:00:00"));
    }

    @Test
    void 기록_없으면_잔량0_총량0_마지막잔과_판정은_null() {
        NightIntake intake = InsightCalc.nightIntake(NIGHT_DATE, BEDTIME, List.of(), SEOUL, HALF_LIFE, REFERENCE_DOSE);
        assertThat(intake.residualAtBedtimeMg()).isZero();
        assertThat(intake.dayTotalMg()).isZero();
        assertThat(intake.lastIntakeAt()).isNull();
        assertThat(intake.cutoffKept()).isNull();
    }

    @Test
    void 짝지은_섭취일은_전날_05시부터_다음날_05시_중_취침_전_기록() {
        // 05-31 04:00 은 전 섭취일(05-30) — 잔량엔 들어가고(24h 안) 짝지은 섭취일엔 안 들어간다.
        // 06-01 00:30 은 취침 뒤 — 둘 다 아니다.
        NightIntake intake = InsightCalc.nightIntake(NIGHT_DATE, BEDTIME, List.of(
                dose("2026-05-31T04:00:00", 100),
                dose("2026-05-31T09:00:00", 150),
                dose("2026-06-01T00:30:00", 80)), SEOUL, HALF_LIFE, REFERENCE_DOSE);
        assertThat(intake.dayTotalMg()).isEqualTo(150.0);
        assertThat(intake.lastIntakeAt()).isEqualTo(kst("2026-05-31T09:00:00"));
        double expected = 100 * Math.pow(2, -19.0 / 5) + 150 * Math.pow(2, -14.0 / 5);
        assertThat(intake.residualAtBedtimeMg()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void 섭취일_기록이_없으면_전날_늦은_잔이_있어도_판정하지_않는다() {
        NightIntake intake = InsightCalc.nightIntake(NIGHT_DATE, BEDTIME,
                List.of(dose("2026-05-31T03:00:00", 100)), SEOUL, HALF_LIFE, REFERENCE_DOSE);
        assertThat(intake.residualAtBedtimeMg()).isGreaterThan(0);
        assertThat(intake.cutoffKept()).isNull();
        assertThat(intake.dayTotalMg()).isZero();
    }

    // ---------------------------------------------------------------- 마감 판정

    @Test
    void 마감판정_CUTOFF_마감_이전이면_지킴_이후면_넘김() {
        // 이전 기록 없음, 기준 75mg, 반감기 5h → 마감 = 취침 − 5·log2(1.5) ≈ 2.925h 전
        assertThat(InsightCalc.cutoffKept(List.of(), BEDTIME.minusSeconds(4 * 3600), BEDTIME, REFERENCE_DOSE, HALF_LIFE))
                .isTrue();
        assertThat(InsightCalc.cutoffKept(List.of(), BEDTIME.minusSeconds(2 * 3600), BEDTIME, REFERENCE_DOSE, HALF_LIFE))
                .isFalse();
    }

    @Test
    void 마감판정_SAFE_ANYTIME이면_지킴() {
        // 기준 용량 40mg < 여유 50mg → 언제 마셔도 안전
        assertThat(InsightCalc.cutoffKept(List.of(), BEDTIME.minusSeconds(600), BEDTIME, 40, HALF_LIFE)).isTrue();
    }

    @Test
    void 마감판정_마지막잔_전에_이미_초과면_넘김() {
        // 200mg 5h 전 → 취침 때 100mg ≥ 50 → ALREADY_EXCEEDED
        List<Dose> prior = List.of(new Dose(BEDTIME.minusSeconds(5 * 3600), 200));
        assertThat(InsightCalc.cutoffKept(prior, BEDTIME.minusSeconds(4 * 3600), BEDTIME, REFERENCE_DOSE, HALF_LIFE))
                .isFalse();
    }

    @Test
    void 마감판정은_마지막잔_이전_기록만_쓴다() {
        // 마지막 잔(19:00 30mg)만 놓고 보면 이전 기록 없음 → 마감 ≈ 20:04 → 지킴. 마지막 잔 자신을 넣으면 결과가 달라진다.
        NightIntake intake = InsightCalc.nightIntake(NIGHT_DATE, BEDTIME,
                List.of(dose("2026-05-31T19:00:00", 30)), SEOUL, HALF_LIFE, REFERENCE_DOSE);
        assertThat(intake.cutoffKept()).isTrue();
    }

    // ---------------------------------------------------------------- 비교

    @Test
    void 리포트_5밤_미만이면_비교도_부족_모자란_밤은_두_그룹_합() {
        ComparisonResult result = InsightCalc.compare(nights(1, 30.0, 0.9), nights(3, 15.0, 0.9), false);
        assertThat(result.sufficient()).isFalse();
        assertThat(result.moreNightsNeeded()).isEqualTo(2);
        assertThat(result.solDiffMinutes()).isNull();
        assertThat(result.efficiencyDiff()).isNull();
        assertThat(result.hasDifference()).isFalse();
        assertThat(InsightCalc.reportSufficient(4)).isFalse();
        assertThat(InsightCalc.reportSufficient(5)).isTrue();
        assertThat(InsightCalc.moreReportNightsNeeded(2)).isEqualTo(3);
    }

    @Test
    void 그룹당_3밤_미만이면_부족() {
        ComparisonResult result = InsightCalc.compare(nights(2, 30.0, 0.9), nights(6, 15.0, 0.9), true);
        assertThat(result.sufficient()).isFalse();
        assertThat(result.moreNightsNeeded()).isEqualTo(1);
    }

    @Test
    void SOL_차이_5분_이상이면_차이있음_평균은_값_있는_밤만() {
        ComparisonResult result = InsightCalc.compare(
                List.of(night(30.0, 0.9), night(20.0, 0.9), night(25.0, 0.9), night(null, 0.9)),
                nights(3, 20.0, 0.9), true);
        assertThat(result.sufficient()).isTrue();
        assertThat(result.moreNightsNeeded()).isNull();
        assertThat(result.high().nightCount()).isEqualTo(4);
        assertThat(result.high().solNightCount()).isEqualTo(3);
        assertThat(result.high().avgSolMinutes()).isEqualTo(25.0);
        assertThat(result.solDiffMinutes()).isEqualTo(5.0);
        assertThat(result.solDifferent()).isTrue();
        assertThat(result.efficiencyDifferent()).isFalse();
    }

    @Test
    void SOL_차이_5분_미만이면_차이없음() {
        ComparisonResult result = InsightCalc.compare(nights(3, 24.9, 0.9), nights(3, 20.0, 0.9), true);
        assertThat(result.solDiffMinutes()).isCloseTo(4.9, within(1e-9));
        assertThat(result.hasDifference()).isFalse();
    }

    @Test
    void SOL_있는_밤이_그룹당_3밤_미만이면_SOL_차이는_null_효율로만_판정() {
        ComparisonResult result = InsightCalc.compare(
                List.of(night(60.0, 0.85), night(60.0, 0.85), night(null, 0.85)),
                nights(3, 10.0, 0.90), true);
        assertThat(result.sufficient()).isTrue();
        assertThat(result.solDiffMinutes()).isNull();
        assertThat(result.efficiencyDiff()).isCloseTo(-0.05, within(1e-9));
        assertThat(result.solDifferent()).isFalse();
        assertThat(result.efficiencyDifferent()).isTrue();
    }

    @Test
    void 효율_차이_3퍼센트포인트_기준() {
        // 이진 표현이 정확한 값으로 경계 양쪽을 본다(0.03 자체는 double 로 정확히 표현되지 않는다)
        assertThat(InsightCalc.compare(nights(3, null, 0.875), nights(3, null, 0.90625), true).efficiencyDifferent())
                .isTrue();   // 0.03125
        assertThat(InsightCalc.compare(nights(3, null, 0.875), nights(3, null, 0.8984375), true).efficiencyDifferent())
                .isFalse();  // 0.0234375
    }

    @Test
    void 빈_그룹의_평균은_null() {
        ComparisonResult result = InsightCalc.compare(List.of(), nights(2, 15.0, 0.9), false);
        assertThat(result.high().nightCount()).isZero();
        assertThat(result.high().avgSolMinutes()).isNull();
        assertThat(result.high().avgSleepEfficiency()).isNull();
        assertThat(result.high().avgTotalSleepMinutes()).isNull();
        assertThat(result.high().avgDeepSleepRatio()).isNull();
        assertThat(result.moreNightsNeeded()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- 섭취 통계

    @Test
    void 하루_집계_400mg_초과만_overLimit() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        DayIntake exactly = InsightCalc.dayIntake(date, List.of(dose("2026-06-01T09:00:00", 400)));
        DayIntake over = InsightCalc.dayIntake(date, List.of(
                dose("2026-06-01T09:00:00", 300), dose("2026-06-01T15:00:00", 101)));
        DayIntake empty = InsightCalc.dayIntake(date, List.of());
        assertThat(exactly.overLimit()).isFalse();
        assertThat(over.overLimit()).isTrue();
        assertThat(over.recordCount()).isEqualTo(2);
        assertThat(over.firstIntakeAt()).isEqualTo(kst("2026-06-01T09:00:00"));
        assertThat(over.lastIntakeAt()).isEqualTo(kst("2026-06-01T15:00:00"));
        assertThat(empty.totalMg()).isZero();
        assertThat(empty.firstIntakeAt()).isNull();
        assertThat(empty.lastIntakeAt()).isNull();
    }

    @Test
    void 요약은_기록_있는_날만_오늘은_제외() {
        LocalDate d1 = LocalDate.of(2026, 6, 1);
        LocalDate d2 = LocalDate.of(2026, 6, 2);
        LocalDate d3 = LocalDate.of(2026, 6, 3);
        LocalDate today = LocalDate.of(2026, 6, 4);
        IntakeSummary summary = InsightCalc.summarize(List.of(
                InsightCalc.dayIntake(d1, List.of(dose("2026-06-01T16:00:00", 100))),
                InsightCalc.dayIntake(d2, List.of()),
                InsightCalc.dayIntake(d3, List.of(dose("2026-06-03T17:00:00", 500))),
                InsightCalc.dayIntake(today, List.of(dose("2026-06-04T09:00:00", 900)))), today, SEOUL);
        assertThat(summary.recordedDayCount()).isEqualTo(3);
        assertThat(summary.averageDailyMg()).isEqualTo(300.0);
        assertThat(summary.maxDailyMg()).isEqualTo(500.0);
        assertThat(summary.overLimitDayCount()).isEqualTo(1);
        assertThat(summary.averageLastIntakeMinuteOfDay()).isEqualTo(16 * 60 + 30);
    }

    @Test
    void 요약할_날이_없으면_null() {
        LocalDate today = LocalDate.of(2026, 6, 4);
        IntakeSummary summary = InsightCalc.summarize(List.of(
                InsightCalc.dayIntake(LocalDate.of(2026, 6, 3), List.of()),
                InsightCalc.dayIntake(today, List.of(dose("2026-06-04T09:00:00", 100)))), today, SEOUL);
        assertThat(summary.recordedDayCount()).isEqualTo(1);
        assertThat(summary.averageDailyMg()).isNull();
        assertThat(summary.maxDailyMg()).isNull();
        assertThat(summary.overLimitDayCount()).isZero();
        assertThat(summary.averageLastIntakeMinuteOfDay()).isNull();
    }

    @Test
    void 마지막잔_시각_평균은_05시_기준이라_자정을_넘어도_끌려내려가지_않는다() {
        // 23:00(05:00 기준 1080분)과 다음 날 01:00(1200분) → 평균 1140분 → 00:00. 0시 기준이면 12:00이 된다.
        LocalDate today = LocalDate.of(2026, 6, 10);
        IntakeSummary summary = InsightCalc.summarize(List.of(
                InsightCalc.dayIntake(LocalDate.of(2026, 6, 1), List.of(dose("2026-06-01T23:00:00", 100))),
                InsightCalc.dayIntake(LocalDate.of(2026, 6, 2), List.of(dose("2026-06-03T01:00:00", 100)))),
                today, SEOUL);
        assertThat(summary.averageLastIntakeMinuteOfDay()).isZero();
    }
}
