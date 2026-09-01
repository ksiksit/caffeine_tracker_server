package com.jongbeom.server.domain.learning.dto;

import com.jongbeom.server.domain.learning.entity.HalfLifeObservation;
import java.time.LocalDate;

public record ObservationResponse(
        LocalDate date,
        double predictedResidualAtBedtime,
        double observedSolMinutes,
        double priorMean,
        double posteriorMean,
        double posteriorVariance,
        double conditionMultiplier
) {
    public static ObservationResponse from(HalfLifeObservation o) {
        return new ObservationResponse(o.getDate(), o.getPredictedResidualAtBedtime(),
                o.getObservedSolMinutes(), o.getPriorMean(), o.getPosteriorMean(),
                o.getPosteriorVariance(), o.getConditionMultiplier());
    }
}
