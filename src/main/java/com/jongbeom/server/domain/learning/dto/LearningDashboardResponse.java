package com.jongbeom.server.domain.learning.dto;

import java.util.List;

/**
 * 학습 대시보드 — 서버가 LearningStatsCalc로 계산한 통계 일체.
 * 카드 분기(0 / 1~2 / ≥3)는 {@code count}로 판단(← iOS LearningDashboardView).
 */
public record LearningDashboardResponse(
        int count,
        LatestEstimate latest,
        List<ObservationResponse> observations,
        Calibration calibration,
        List<HistogramBinItem> residualHistogram,
        List<HistogramBinItem> solHistogram
) {
    public record LatestEstimate(
            double posteriorMean,
            double posteriorVariance,
            double ciLower,
            double ciUpper,
            double confidence
    ) {
    }

    public record Calibration(
            List<PointItem> points,
            Double rSquared,
            double rmse,
            double domainLower,
            double domainUpper
    ) {
    }

    public record PointItem(double predicted, double observed) {
    }

    public record HistogramBinItem(double lower, double upper, int count) {
    }
}
