package com.jongbeom.server.learning.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 학습 대시보드 — 서버가 LearningStatsCalc로 계산한 통계 일체.
 * 카드 분기(0 / 1~2 / ≥3)는 {@code count}로 판단(← iOS LearningDashboardView).
 */
@Schema(description = "학습 대시보드(서버 계산)")
public record LearningDashboardResponse(
        @Schema(description = "관측 수") int count,
        @Schema(description = "최신 추정(count=0이면 null)") LatestEstimate latest,
        @Schema(description = "관측 이력(날짜 오름차순)") List<ObservationResponse> observations,
        @Schema(description = "모델 정확도(calibration)") Calibration calibration,
        @Schema(description = "취침 잔량 히스토그램") List<HistogramBinItem> residualHistogram,
        @Schema(description = "측정 SOL 히스토그램") List<HistogramBinItem> solHistogram
) {
    @Schema(description = "최신 추정")
    public record LatestEstimate(
            @Schema(description = "사후 평균(h)") double posteriorMean,
            @Schema(description = "사후 분산(h²)") double posteriorVariance,
            @Schema(description = "95% CI 하한(h)") double ciLower,
            @Schema(description = "95% CI 상한(h)") double ciUpper,
            @Schema(description = "신뢰도(0~1)") double confidence
    ) {
    }

    @Schema(description = "calibration: 예측 SOL vs 관측 SOL")
    public record Calibration(
            @Schema(description = "산점도 점") List<PointItem> points,
            @Schema(description = "R²(N<3 또는 분산0이면 null)") Double rSquared,
            @Schema(description = "RMSE(분)") double rmse,
            @Schema(description = "축 도메인 하한") double domainLower,
            @Schema(description = "축 도메인 상한") double domainUpper
    ) {
    }

    public record PointItem(double predicted, double observed) {
    }

    public record HistogramBinItem(double lower, double upper, int count) {
    }
}
