package com.jongbeom.server.learning.dto;

import com.jongbeom.server.learning.HalfLifeObservation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "베이지안 학습 관측 1건")
public record ObservationResponse(
        @Schema(description = "관측 날짜(아침 기준)") LocalDate date,
        @Schema(description = "취침 시 예상 카페인 잔량(mg)") double predictedResidualAtBedtime,
        @Schema(description = "측정된 수면 잠복기(분)") double observedSolMinutes,
        @Schema(description = "갱신 전 prior 평균(h)") double priorMean,
        @Schema(description = "갱신 후 posterior 평균(h)") double posteriorMean,
        @Schema(description = "갱신 후 posterior 분산(h²)") double posteriorVariance,
        @Schema(description = "건강상태 multiplier") double conditionMultiplier
) {
    public static ObservationResponse from(HalfLifeObservation o) {
        return new ObservationResponse(o.getDate(), o.getPredictedResidualAtBedtime(),
                o.getObservedSolMinutes(), o.getPriorMean(), o.getPosteriorMean(),
                o.getPosteriorVariance(), o.getConditionMultiplier());
    }
}
