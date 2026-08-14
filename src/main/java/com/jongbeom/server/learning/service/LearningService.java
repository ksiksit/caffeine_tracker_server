package com.jongbeom.server.learning.service;

import com.jongbeom.server.caffeine.repository.CaffeineRecordRepository;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater.Linearization;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater.Posterior;
import com.jongbeom.server.calc.BedtimeExtractor;
import com.jongbeom.server.calc.LearningStatsCalc;
import com.jongbeom.server.calc.LearningStatsCalc.Point;
import com.jongbeom.server.calc.LearningStatsCalc.Range;
import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import com.jongbeom.server.learning.LearningSkipReason;
import com.jongbeom.server.learning.dto.LearningDashboardResponse;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.Calibration;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.HistogramBinItem;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.LatestEstimate;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.PointItem;
import com.jongbeom.server.learning.dto.LearningRunResponse;
import com.jongbeom.server.learning.dto.ObservationResponse;
import com.jongbeom.server.learning.entity.HalfLifeObservation;
import com.jongbeom.server.learning.repository.HalfLifeObservationRepository;
import com.jongbeom.server.settings.entity.CaffeineCondition;
import com.jongbeom.server.settings.entity.UserSettings;
import com.jongbeom.server.settings.service.UserSettingsService;
import com.jongbeom.server.sleep.service.SleepService;
import com.jongbeom.server.sleep.service.SleepService.NightData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 베이지안 반감기 학습 실행(run) + 관측 이력·대시보드 조회. */
@Service
@RequiredArgsConstructor
public class LearningService {

    /** 후보 night 룩백(일). iOS AppConstants.Sleep.recentHistoryDays. */
    private static final int RECENT_DAYS = 7;
    /** 대시보드 히스토그램 빈 수 (← iOS 차트 구성). */
    private static final int HISTOGRAM_BINS = 6;
    /** 95% 신뢰구간 z-값. */
    private static final double Z_SCORE_95 = 1.96;

    private final UserSettingsService settingsService;
    private final SleepService sleepService;
    private final CaffeineRecordRepository caffeineRepository;
    private final HalfLifeObservationRepository observationRepository;
    private final Clock clock;

    /**
     * 최근 7일 중 미학습 night를 오래된→최신 순으로 배치 학습.
     * **순차 prior 체이닝**: 매 night마다 settings의 갱신된 learned 값을 prior로 사용한다.
     * (← iOS updateMissingObservations)
     */
    @Transactional
    public LearningRunResponse run(Long userId, ZoneId zone) {
        UserSettings settings = settingsService.getOrCreate(userId);
        if (!settings.isLearningEnabled()) {
            return toRunResponse(0, LearningSkipReason.LEARNING_DISABLED);
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate oldestCandidate = today.minusDays(RECENT_DAYS - 1);
        int updated = 0;
        boolean anySleepData = false;
        // 데이터 있는 날만 처리하고, 더 최신 날짜의 skip 사유가 이전 사유를 덮어쓰도록 둔다
        // (← iOS는 summaries(데이터 있는 날)만 받아 latest date 사유를 표시).
        LearningSkipReason latestReason = null;

        for (LocalDate date = oldestCandidate; !date.isAfter(today); date = date.plusDays(1)) {
            NightData night = sleepService.mergedNight(userId, date, zone);
            if (night.isEmpty()) {
                continue; // 데이터 없는 날은 조용히 건너뜀
            }
            anySleepData = true;

            LearningSkipReason skipReason = learnOneNight(userId, date, zone, settings, night);
            if (skipReason == null) {
                updated++;
            } else {
                latestReason = skipReason;
            }
        }

        if (!anySleepData) {
            latestReason = LearningSkipReason.NO_SLEEP_SUMMARY;
        }
        // 하나라도 학습됐으면 사유는 보고하지 않는다 (← iOS 동일 규칙)
        return toRunResponse(updated, updated == 0 ? latestReason : null);
    }

    /**
     * 한 night 학습 시도. 성공하면 관측 저장 + settings 반영 후 null, 실패하면 skip 사유 반환.
     * settings의 learned 값을 그대로 prior로 읽으므로, 직전 night의 성공 갱신이 다음 night의 prior가 된다(순차 체이닝).
     */
    private LearningSkipReason learnOneNight(
            Long userId, LocalDate date, ZoneId zone, UserSettings settings, NightData night) {
        if (observationRepository.existsByUserIdAndDate(userId, date)) {
            return LearningSkipReason.ALREADY_LEARNED;
        }

        BedtimeExtractor.Result bedtimeResult = BedtimeExtractor.extract(
                night.intervals(), night.bedtimeStart(),
                settings.getBedtimeHour(), settings.getBedtimeMinute(), date, zone);
        if (!bedtimeResult.isOk()) {
            return LearningSkipReason.from(bedtimeResult.failure());
        }

        double priorMean = settings.getLearnedMean();
        double priorVariance = settings.getLearnedVariance();
        double multiplier = CaffeineCondition.fromRawValue(settings.getCondition()).multiplier();
        if (!hasValidPrior(priorMean, priorVariance, multiplier)) {
            return LearningSkipReason.INVALID_PRIOR;
        }

        Instant bedtime = bedtimeResult.bedtime();
        List<Dose> doses = dosesBefore(userId, bedtime);

        Linearization linearization = BayesianHalfLifeUpdater.linearize(bedtime, doses, priorMean, multiplier);
        if (linearization == null) {
            return LearningSkipReason.INSUFFICIENT_CAFFEINE_RESIDUAL;
        }
        Posterior rawPosterior = BayesianHalfLifeUpdater.posteriorEstimate(
                priorMean, priorVariance, linearization, bedtimeResult.solMinutes());
        Posterior boundedPosterior = BayesianHalfLifeUpdater.applySafetyBounds(
                rawPosterior.mean(), rawPosterior.variance(), priorMean);

        // 생성 시점 검증 실패를 skip으로 처리 — iOS 제어흐름을 그대로 미러링(의도적 try/catch)
        try {
            HalfLifeObservation observation = HalfLifeObservation.create(
                    userId, date, linearization.cMu(), bedtimeResult.solMinutes(),
                    new HalfLifeObservation.Prior(priorMean, priorVariance),
                    new HalfLifeObservation.Posterior(boundedPosterior.mean(), boundedPosterior.variance()),
                    multiplier);
            observationRepository.save(observation);
            settings.applyLearning(boundedPosterior.mean(), boundedPosterior.variance(), date); // 저장 성공 후에만 갱신
            return null;
        } catch (IllegalArgumentException e) {
            return LearningSkipReason.INVALID_OBSERVATION;
        }
    }

    private static boolean hasValidPrior(double mean, double variance, double multiplier) {
        return mean > 0 && variance > 0 && multiplier > 0;
    }

    /** bedtime 직전 룩백 윈도우(24h)의 카페인 기록을 Dose로 변환. */
    private List<Dose> dosesBefore(Long userId, Instant bedtime) {
        Instant lookbackStart = bedtime.minus(
                Duration.ofHours((long) BayesianHalfLifeUpdater.DOSES_LOOKBACK_HOURS));
        return caffeineRepository
                .findByUserIdAndTimestampBetweenOrderByTimestampAsc(userId, lookbackStart, bedtime)
                .stream().map(record -> new Dose(record.getTimestamp(), record.getAmount())).toList();
    }

    @Transactional(readOnly = true)
    public List<ObservationResponse> observations(Long userId) {
        return observationRepository.findByUserIdOrderByDateAsc(userId)
                .stream().map(ObservationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public LearningDashboardResponse dashboard(Long userId) {
        List<HalfLifeObservation> observations = observationRepository.findByUserIdOrderByDateAsc(userId);
        int count = observations.size();

        LatestEstimate latest = count >= 1 ? toLatestEstimate(observations.get(count - 1)) : null;

        List<Point> points = observations.stream()
                .map(o -> new Point(LearningStatsCalc.predictedSOL(o.getPredictedResidualAtBedtime()),
                        o.getObservedSolMinutes()))
                .toList();
        Range domain = LearningStatsCalc.calibrationDomain(points);
        List<PointItem> pointItems = points.stream()
                .map(p -> new PointItem(p.predicted(), p.observed()))
                .toList();
        Calibration calibration = new Calibration(
                pointItems, LearningStatsCalc.rSquared(points), LearningStatsCalc.rmse(points),
                domain.lower(), domain.upper());

        List<ObservationResponse> observationItems = observations.stream()
                .map(ObservationResponse::from).toList();
        List<HistogramBinItem> residualHistogram = histogram(
                observations.stream().map(HalfLifeObservation::getPredictedResidualAtBedtime).toList());
        List<HistogramBinItem> solHistogram = histogram(
                observations.stream().map(HalfLifeObservation::getObservedSolMinutes).toList());

        return new LearningDashboardResponse(
                count, latest, observationItems, calibration, residualHistogram, solHistogram);
    }

    /** 최신 관측의 posterior + 95% 신뢰구간(±z·σ). */
    private static LatestEstimate toLatestEstimate(HalfLifeObservation latest) {
        double halfWidth = Z_SCORE_95 * Math.sqrt(latest.getPosteriorVariance());
        return new LatestEstimate(
                latest.getPosteriorMean(), latest.getPosteriorVariance(),
                latest.getPosteriorMean() - halfWidth, latest.getPosteriorMean() + halfWidth,
                LearningStatsCalc.confidenceScore(latest.getPosteriorVariance()));
    }

    private List<HistogramBinItem> histogram(List<Double> values) {
        return LearningStatsCalc.histogram(values, HISTOGRAM_BINS).stream()
                .map(b -> new HistogramBinItem(b.lower(), b.upper(), b.count()))
                .toList();
    }

    private LearningRunResponse toRunResponse(int updated, LearningSkipReason reason) {
        return new LearningRunResponse(updated,
                reason == null ? null : reason.name(),
                reason == null ? null : reason.message());
    }
}
