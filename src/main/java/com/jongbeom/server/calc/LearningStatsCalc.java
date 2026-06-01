package com.jongbeom.server.calc;

import com.jongbeom.server.settings.UserSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * 학습 대시보드용 파생 통계. iOS {@code LearningStats} 이관(순수 함수).
 * α/β는 {@link BayesianHalfLifeUpdater}와 동일해야 calibration이 일관됨.
 */
public final class LearningStatsCalc {

    private LearningStatsCalc() {
    }

    /** SOL = α + β·residual (Drake 2013). BayesianHalfLifeUpdater와 동기화. */
    public static final double ALPHA = 15.0;
    public static final double BETA = 0.10;

    public record Point(double predicted, double observed) {
    }

    public record HistogramBin(double lower, double upper, int count) {
    }

    public record Range(double lower, double upper) {
    }

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

    /** R² 결정계수. N<3 또는 SS_tot=0이면 null. */
    public static Double rSquared(List<Point> points) {
        if (points.size() < 3) {
            return null;
        }
        double mean = points.stream().mapToDouble(Point::observed).average().orElse(0);
        double ssTot = 0;
        double ssRes = 0;
        for (Point p : points) {
            ssTot += (p.observed() - mean) * (p.observed() - mean);
            ssRes += (p.observed() - p.predicted()) * (p.observed() - p.predicted());
        }
        if (ssTot <= 0) {
            return null;
        }
        return 1 - ssRes / ssTot;
    }

    /** 평균제곱오차의 제곱근. N=0이면 0. */
    public static double rmse(List<Point> points) {
        if (points.isEmpty()) {
            return 0;
        }
        double ss = 0;
        for (Point p : points) {
            ss += (p.observed() - p.predicted()) * (p.observed() - p.predicted());
        }
        return Math.sqrt(ss / points.size());
    }

    /** 양 축 공통 도메인. 5분 패딩, 0 미만은 0. 빈 입력은 0~60. */
    public static Range calibrationDomain(List<Point> points) {
        if (points.isEmpty()) {
            return new Range(0, 60);
        }
        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        for (Point p : points) {
            minVal = Math.min(minVal, Math.min(p.predicted(), p.observed()));
            maxVal = Math.max(maxVal, Math.max(p.predicted(), p.observed()));
        }
        double lo = Math.max(0, minVal - 5);
        double hi = maxVal + 5;
        return new Range(lo, Math.max(hi, lo + 10));
    }

    /**
     * values를 binCount개 동일 폭 빈으로 분할. 빈 입력/binCount&lt;1 → []. 모두 동일 → 단일 빈.
     * 마지막 빈 상한 inclusive. (← LearningStats.histogram)
     */
    public static List<HistogramBin> histogram(List<Double> values, int binCount) {
        if (values.isEmpty() || binCount < 1) {
            return List.of();
        }
        double lo = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double hi = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (hi <= lo) {
            return List.of(new HistogramBin(lo, lo, values.size()));
        }
        double width = (hi - lo) / binCount;
        int[] counts = new int[binCount];
        for (double v : values) {
            int idx = Math.min((int) ((v - lo) / width), binCount - 1);
            counts[idx]++;
        }
        List<HistogramBin> bins = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            bins.add(new HistogramBin(lo + width * i, lo + width * (i + 1), counts[i]));
        }
        return bins;
    }
}
