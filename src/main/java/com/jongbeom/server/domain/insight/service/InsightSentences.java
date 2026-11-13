package com.jongbeom.server.domain.insight.service;

import com.jongbeom.server.domain.calc.insight.InsightCalc;
import com.jongbeom.server.domain.calc.insight.InsightCalc.ComparisonResult;
import com.jongbeom.server.domain.calc.insight.InsightCalc.IntakeSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 인사이트 문장(한국어 고정). 규칙의 원본은 api.md 인사이트 절 — 여기를 바꾸면 그 표도 고친다.
 * 비교 결과를 "~했어요"로 서술만 하고 인과·통계 단정 표현은 쓰지 않는다.
 */
final class InsightSentences {

    private InsightSentences() {
    }

    private static final long RESIDUAL_THRESHOLD = Math.round(InsightCalc.RESIDUAL_THRESHOLD_MG);

    // ---------------------------------------------------------------- 섭취 통계

    static String intakeSummary(LocalDate from, LocalDate to, LocalDate today, int dayCount, IntakeSummary summary) {
        String period = to.equals(today)
                ? "최근 " + dayCount + "일"
                : from.getMonthValue() + "월 " + from.getDayOfMonth() + "일~" + to.getMonthValue() + "월 " + to.getDayOfMonth() + "일";
        if (summary.averageDailyMg() == null) {
            return period + " 하루 평균을 낼 섭취 기록이 아직 없어요.";
        }
        String overLimit = summary.overLimitDayCount() == 0
                ? "400mg을 넘긴 날은 없어요."
                : "400mg을 넘긴 날은 " + summary.overLimitDayCount() + "일이에요.";
        int minuteOfDay = summary.averageLastIntakeMinuteOfDay();
        int hour = minuteOfDay / 60;
        int minute = minuteOfDay % 60;
        String clock = minute == 0 ? hour + "시예요." : hour + "시 " + minute + "분이에요.";
        return period + " 하루 평균 " + Math.round(summary.averageDailyMg()) + "mg, " + overLimit
                + " 마지막 한 잔은 평균 " + clock;
    }

    // ---------------------------------------------------------------- 비교 A

    static String residualComparison(ComparisonResult result) {
        if (!result.sufficient()) {
            return "비교하려면 " + shortage(result, "잔량이 높았던 밤이", "잔량이 낮았던 밤이", "낮았던 밤이") + " 더 필요해요";
        }
        String counts = " (" + result.high().nightCount() + "밤 vs " + result.low().nightCount() + "밤)";
        String subject = "취침 때 카페인이 " + RESIDUAL_THRESHOLD + "mg 이상 남아 있던 밤은 ";
        if (result.solDifferent()) {
            String sol = "잠드는 데 평균 " + minutes(result.solDiffMinutes()) + "분 " + moreOrLess(result.solDiffMinutes()) + " 걸렸";
            if (result.efficiencyDifferent()) {
                return subject + sol + "고, 수면 효율은 " + percentPoints(result.efficiencyDiff()) + "%p "
                        + lowerOrHigher(result.efficiencyDiff()) + counts;
            }
            return subject + sol + "어요" + counts;
        }
        if (result.efficiencyDifferent()) {
            return subject + "수면 효율이 평균 " + percentPoints(result.efficiencyDiff()) + "%p "
                    + lowerOrHigher(result.efficiencyDiff()) + counts;
        }
        return "취침 잔량에 따른 뚜렷한 차이는 아직 보이지 않아요" + counts;
    }

    // ---------------------------------------------------------------- 비교 B

    /** 지금 설정 기준 판정임을 알리려고 항상 기준 용량 문구로 시작한다(★4). high = 넘김, low = 지킴. */
    static String cutoffComparison(ComparisonResult result, int referenceDoseMg) {
        String prefix = "지금 기준 용량(" + referenceDoseMg + "mg)으로 따지면, ";
        if (!result.sufficient()) {
            return prefix + shortage(result, "마감을 넘긴 밤이", "마감을 지킨 밤이", "지킨 밤이") + " 더 있어야 비교할 수 있어요";
        }
        String counts = " (지킨 밤 " + result.low().nightCount() + "밤 · 넘긴 밤 " + result.high().nightCount() + "밤)";
        if (result.solDifferent()) {
            String sol = "마감 시각을 지킨 밤은 평균 " + Math.round(result.low().avgSolMinutes()) + "분 만에 잠들었고 넘긴 밤은 "
                    + Math.round(result.high().avgSolMinutes()) + "분 걸렸어요";
            if (result.efficiencyDifferent()) {
                return prefix + sol + ". 수면 효율은 넘긴 밤이 " + percentPoints(result.efficiencyDiff()) + "%p "
                        + lowerOrHigher(result.efficiencyDiff()) + counts;
            }
            return prefix + sol + counts;
        }
        if (result.efficiencyDifferent()) {
            return prefix + "마감 시각을 넘긴 밤은 지킨 밤보다 수면 효율이 평균 " + percentPoints(result.efficiencyDiff()) + "%p "
                    + lowerOrHigher(result.efficiencyDiff()) + counts;
        }
        return prefix + "마감 시각을 지켰는지에 따른 뚜렷한 차이는 아직 보이지 않아요" + counts;
    }

    // ---------------------------------------------------------------- 상단 문장

    /** 5밤 미만이면 표본 부족 한 줄. 아니면 차이 있는 비교 문장(A, B 순), 없으면 비교 A 문장 하나. */
    static List<String> headlines(int nightCount, ComparisonResult residual, String residualSentence,
                                  ComparisonResult cutoff, String cutoffSentence) {
        if (!InsightCalc.reportSufficient(nightCount)) {
            return List.of("리포트를 보려면 수면 기록이 있는 밤이 "
                    + InsightCalc.moreReportNightsNeeded(nightCount) + "밤 더 필요해요");
        }
        List<String> headlines = new ArrayList<>();
        if (residual.hasDifference()) {
            headlines.add(residualSentence);
        }
        if (cutoff.hasDifference()) {
            headlines.add(cutoffSentence);
        }
        if (headlines.isEmpty()) {
            headlines.add(residualSentence);
        }
        return List.copyOf(headlines);
    }

    // ---------------------------------------------------------------- 공통

    /** 모자란 그룹: high 만 → "{high} N밤", low 만 → "{low} N밤", 둘 다 → "{high} N밤, {lowShort} M밤". */
    private static String shortage(ComparisonResult result, String highLabel, String lowLabel, String lowShortLabel) {
        int highShort = Math.max(0, InsightCalc.MIN_GROUP_NIGHTS - result.high().nightCount());
        int lowShort = Math.max(0, InsightCalc.MIN_GROUP_NIGHTS - result.low().nightCount());
        if (highShort > 0 && lowShort > 0) {
            return highLabel + " " + highShort + "밤, " + lowShortLabel + " " + lowShort + "밤";
        }
        return highShort > 0 ? highLabel + " " + highShort + "밤" : lowLabel + " " + lowShort + "밤";
    }

    private static long minutes(double diff) {
        return Math.round(Math.abs(diff));
    }

    private static long percentPoints(double efficiencyDiff) {
        return Math.round(Math.abs(efficiencyDiff) * 100);
    }

    private static String moreOrLess(double diff) {
        return diff > 0 ? "더" : "덜";
    }

    private static String lowerOrHigher(double diff) {
        return diff < 0 ? "낮았어요" : "높았어요";
    }
}
