package com.jongbeom.server.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jongbeom.server.calc.LearningStatsCalc.HistogramBin;
import com.jongbeom.server.calc.LearningStatsCalc.Point;
import com.jongbeom.server.calc.LearningStatsCalc.Range;
import java.util.List;
import org.junit.jupiter.api.Test;

/** iOS {@code LearningStatsTests.swift} 골든값 복제. */
class LearningStatsCalcTest {

    private static Point point(double predicted, double observed) {
        return new Point(predicted, observed);
    }

    // predictedSOL
    @Test
    void predictedSOL() {
        assertThat(LearningStatsCalc.predictedSOL(0)).isEqualTo(15.0);   // α=15 (0mg일 때 기본 SOL)
        assertThat(LearningStatsCalc.predictedSOL(100)).isEqualTo(25.0); // 15 + 0.10×100
    }

    // confidenceScore
    @Test
    void confidence_stillAtPopulationPrior_scoresZero() {
        // 2.25 = 모집단 prior 분산 1.5² — 아직 아무것도 못 배운 상태라 신뢰도 0
        assertThat(LearningStatsCalc.confidenceScore(2.25)).isCloseTo(0, within(1e-9));
    }

    @Test
    void confidence_zeroVariance_one() {
        assertThat(LearningStatsCalc.confidenceScore(0)).isEqualTo(1.0);
    }

    @Test
    void confidence_largerThanPrior_clampsZero() {
        assertThat(LearningStatsCalc.confidenceScore(100)).isEqualTo(0);
    }

    @Test
    void confidence_negativeVariance_clampsOne() {
        assertThat(LearningStatsCalc.confidenceScore(-1)).isEqualTo(1.0);
    }

    @Test
    void confidence_atFloor_highlyConfident() {
        double score = LearningStatsCalc.confidenceScore(0.0025); // VARIANCE_FLOOR = 0.05²
        assertThat(score).isCloseTo(1 - 0.05 / 1.5, within(1e-9)); // 1.5 = 모집단 σ_prior
        assertThat(score).isGreaterThan(0.96).isLessThan(0.97);
    }

    // rSquared
    @Test
    void rSquared_perfectFit_one() {
        Double r2 = LearningStatsCalc.rSquared(List.of(point(10, 10), point(20, 20), point(30, 30)));
        assertThat(r2).isNotNull();
        assertThat(r2).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void rSquared_meanBaseline_zero() {
        Double r2 = LearningStatsCalc.rSquared(List.of(point(20, 10), point(20, 20), point(20, 30)));
        assertThat(r2).isNotNull();
        assertThat(r2).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void rSquared_worseThanMean_negative() {
        Double r2 = LearningStatsCalc.rSquared(List.of(point(30, 10), point(10, 20), point(50, 30)));
        assertThat(r2).isNotNull().matches(v -> v < 0);
    }

    @Test
    void rSquared_lessThanThree_null() {
        assertThat(LearningStatsCalc.rSquared(List.of(point(10, 10), point(20, 20)))).isNull();
    }

    @Test
    void rSquared_empty_null() {
        assertThat(LearningStatsCalc.rSquared(List.of())).isNull();
    }

    @Test
    void rSquared_zeroVariance_null() {
        assertThat(LearningStatsCalc.rSquared(List.of(point(10, 20), point(30, 20), point(50, 20)))).isNull();
    }

    // rmse
    @Test
    void rmse_perfectFit_zero() {
        assertThat(LearningStatsCalc.rmse(List.of(point(1, 1), point(2, 2), point(3, 3)))).isZero();
    }

    @Test
    void rmse_knownErrors() {
        double r = LearningStatsCalc.rmse(List.of(point(0, 3), point(0, 4), point(5, 5)));
        assertThat(r).isCloseTo(Math.sqrt(25.0 / 3.0), within(1e-9));
    }

    @Test
    void rmse_empty_zero() {
        assertThat(LearningStatsCalc.rmse(List.of())).isZero();
    }

    // calibrationDomain
    @Test
    void calibrationDomain_empty_default() {
        Range d = LearningStatsCalc.calibrationDomain(List.of());
        assertThat(d.lower()).isEqualTo(0);
        assertThat(d.upper()).isEqualTo(60);
    }

    @Test
    void calibrationDomain_padding() {
        Range d = LearningStatsCalc.calibrationDomain(List.of(point(20, 30), point(40, 50)));
        assertThat(d.lower()).isEqualTo(15);
        assertThat(d.upper()).isEqualTo(55);
    }

    @Test
    void calibrationDomain_clampsLowerZero() {
        Range d = LearningStatsCalc.calibrationDomain(List.of(point(2, 1)));
        assertThat(d.lower()).isEqualTo(0);
    }

    // histogram
    @Test
    void histogram_empty() {
        assertThat(LearningStatsCalc.histogram(List.of(), 5)).isEmpty();
    }

    @Test
    void histogram_zeroBin() {
        assertThat(LearningStatsCalc.histogram(List.of(1.0, 2.0, 3.0), 0)).isEmpty();
    }

    @Test
    void histogram_allSame_singleBin() {
        List<HistogramBin> bins = LearningStatsCalc.histogram(List.of(5.0, 5.0, 5.0, 5.0), 3);
        assertThat(bins).hasSize(1);
        assertThat(bins.get(0).count()).isEqualTo(4);
        assertThat(bins.get(0).lower()).isEqualTo(5);
        assertThat(bins.get(0).upper()).isEqualTo(5);
    }

    @Test
    void histogram_uniform_evenBins() {
        List<HistogramBin> bins = LearningStatsCalc.histogram(List.of(0.0, 10.0, 20.0, 30.0, 40.0), 5);
        assertThat(bins).hasSize(5);
        assertThat(bins).allMatch(b -> b.count() == 1);
    }

    @Test
    void histogram_maxIntoLastBin() {
        List<HistogramBin> bins = LearningStatsCalc.histogram(List.of(0.0, 5.0, 10.0), 2);
        assertThat(bins).hasSize(2);
        assertThat(bins.get(0).count()).isEqualTo(1);
        assertThat(bins.get(1).count()).isEqualTo(2);
    }

    @Test
    void histogram_totalPreserved() {
        List<Double> values = List.of(1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0, 15.0);
        int total = LearningStatsCalc.histogram(values, 4).stream().mapToInt(HistogramBin::count).sum();
        assertThat(total).isEqualTo(values.size());
    }
}
