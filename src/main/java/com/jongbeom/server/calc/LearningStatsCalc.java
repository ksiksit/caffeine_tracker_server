package com.jongbeom.server.calc;

import com.jongbeom.server.settings.entity.UserSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * 학습 대시보드용 파생 통계. iOS {@code LearningStats} 이관(순수 함수).
 * α/β는 {@link BayesianHalfLifeUpdater}와 동일해야 calibration이 일관됨.
 */
public final class LearningStatsCalc {

    private LearningStatsCalc() {
    }

    /** SOL = α + β·residual (Drake 2013). {@link BayesianHalfLifeUpdater} 값을 참조해 동기화 보장. */
    public static final double ALPHA = BayesianHalfLifeUpdater.ALPHA;
    public static final double BETA = BayesianHalfLifeUpdater.BETA;

    /** R² 계산에 필요한 최소 관측 수. */
    private static final int MIN_POINTS_FOR_R_SQUARED = 3;
    /** calibration 축 여백(분). */
    private static final double AXIS_PADDING_MINUTES = 5;
    /** calibration 축 최소 스팬(분) — 점이 몰려도 축이 뭉개지지 않게. */
    private static final double MIN_DOMAIN_SPAN_MINUTES = 10;
    /** 데이터 없을 때 기본 축 도메인(분). */
    private static final double DEFAULT_DOMAIN_LOWER_MINUTES = 0;
    private static final double DEFAULT_DOMAIN_UPPER_MINUTES = 60;

    /** calibration 산점도 한 점. predicted/observed 모두 SOL(분). */
    public record Point(double predicted, double observed) {
    }

    public record HistogramBin(double lower, double upper, int count) {
    }

    /** calibration 축 도메인(분). x·y 양 축이 공유한다(y=x 기준선용). */
    public record Range(double lower, double upper) {
    }

    /** 예측 SOL(분). residualMg는 취침 시 잔량(mg). */
    public static double predictedSOL(double residualMg) {
        return ALPHA + BETA * residualMg;
    }

    /** 1 − σ_post/σ_prior_initial. σ_prior_initial = √2.25 = 1.5h. clamp[0,1]. */
    public static double confidenceScore(double posteriorVariance) {
        double sigmaPost = Math.sqrt(Math.max(posteriorVariance, 0));
        double sigmaPriorInitial = Math.sqrt(UserSettings.POPULATION_PRIOR_VARIANCE);
        double raw = 1.0 - sigmaPost / sigmaPriorInitial;
        return Math.min(Math.max(raw, 0), 1);
    }

    /** R² 결정계수. N&lt;{@value #MIN_POINTS_FOR_R_SQUARED} 또는 SS_tot=0이면 null. */
    public static Double rSquared(List<Point> points) {
        if (points.size() < MIN_POINTS_FOR_R_SQUARED) {
            return null;
        }
        double mean = points.stream().mapToDouble(Point::observed).average().orElse(0);
        double ssTot = 0;
        double ssRes = 0;
        for (Point point : points) {
            ssTot += squaredDeviation(point.observed(), mean);
            ssRes += squaredError(point);
        }
        if (ssTot <= 0) {
            return null;
        }
        return 1 - ssRes / ssTot;
    }

    /** 평균제곱오차의 제곱근(분). N=0이면 0. */
    public static double rmse(List<Point> points) {
        if (points.isEmpty()) {
            return 0;
        }
        double sumSquaredError = 0;
        for (Point point : points) {
            sumSquaredError += squaredError(point);
        }
        return Math.sqrt(sumSquaredError / points.size());
    }

    private static double squaredError(Point point) {
        return (point.observed() - point.predicted()) * (point.observed() - point.predicted());
    }

    private static double squaredDeviation(double value, double mean) {
        return (value - mean) * (value - mean);
    }

    /** 양 축 공통 도메인(분). {@value #AXIS_PADDING_MINUTES}분 패딩, 0 미만은 0. 빈 입력은 0~60. */
    public static Range calibrationDomain(List<Point> points) {
        if (points.isEmpty()) {
            return new Range(DEFAULT_DOMAIN_LOWER_MINUTES, DEFAULT_DOMAIN_UPPER_MINUTES);
        }
        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        for (Point point : points) {
            minVal = Math.min(minVal, Math.min(point.predicted(), point.observed()));
            maxVal = Math.max(maxVal, Math.max(point.predicted(), point.observed()));
        }
        double lower = Math.max(0, minVal - AXIS_PADDING_MINUTES);
        double upper = maxVal + AXIS_PADDING_MINUTES;
        double upperWithMinimumSpan = Math.max(upper, lower + MIN_DOMAIN_SPAN_MINUTES);
        return new Range(lower, upperWithMinimumSpan);
    }

    /**
     * values를 binCount개 동일 폭 빈으로 분할해 하한 오름차순·연속 구간으로 반환.
     * 빈 입력/binCount&lt;1 → []. 모두 동일 → 단일 빈.
     * 마지막 빈 상한 inclusive. (← LearningStats.histogram)
     */
    public static List<HistogramBin> histogram(List<Double> values, int binCount) {
        if (values.isEmpty() || binCount < 1) {
            return List.of();
        }
        double lower = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double upper = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (upper <= lower) {
            return List.of(new HistogramBin(lower, lower, values.size()));
        }
        double width = (upper - lower) / binCount;
        int[] counts = new int[binCount];
        for (double value : values) {
            // 최대값은 bin[binCount]에 떨어지므로 마지막 빈으로 접음(상한 inclusive)
            int binIndex = Math.min((int) ((value - lower) / width), binCount - 1);
            counts[binIndex]++;
        }
        List<HistogramBin> bins = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            bins.add(new HistogramBin(lower + width * i, lower + width * (i + 1), counts[i]));
        }
        return bins;
    }
}
