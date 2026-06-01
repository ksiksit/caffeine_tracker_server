package com.jongbeom.server.learning;

import com.jongbeom.server.caffeine.CaffeineRecordRepository;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater.Linearization;
import com.jongbeom.server.calc.BayesianHalfLifeUpdater.Posterior;
import com.jongbeom.server.calc.BedtimeExtractor;
import com.jongbeom.server.calc.LearningStatsCalc;
import com.jongbeom.server.calc.LearningStatsCalc.Point;
import com.jongbeom.server.calc.LearningStatsCalc.Range;
import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import com.jongbeom.server.learning.dto.LearningDashboardResponse;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.Calibration;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.HistogramBinItem;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.LatestEstimate;
import com.jongbeom.server.learning.dto.LearningDashboardResponse.PointItem;
import com.jongbeom.server.learning.dto.LearningRunResponse;
import com.jongbeom.server.learning.dto.ObservationResponse;
import com.jongbeom.server.settings.CaffeineCondition;
import com.jongbeom.server.settings.UserSettings;
import com.jongbeom.server.settings.UserSettingsService;
import com.jongbeom.server.sleep.SleepService;
import com.jongbeom.server.sleep.SleepService.NightData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningService {

    /** 후보 night 룩백(일). iOS AppConstants.Sleep.recentHistoryDays. */
    private static final int RECENT_DAYS = 7;
    private static final int HISTOGRAM_BINS = 6;

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
            return response(0, LearningSkipReason.LEARNING_DISABLED);
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        int updated = 0;
        boolean anySleepData = false;
        // 오래된→최신 순회. 데이터 있는 날만 처리하고, 더 최신 날짜의 skip 사유가 덮어쓰도록 둔다
        // (← iOS는 summaries(데이터 있는 날)만 받아 latest date 사유를 표시).
        LearningSkipReason latestReason = null;

        for (int offset = RECENT_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);

            NightData night = sleepService.mergedNight(userId, date, zone);
            if (night.isEmpty()) {
                continue; // 데이터 없는 날은 조용히 건너뜀
            }
            anySleepData = true;

            if (observationRepository.existsByUserIdAndDate(userId, date)) {
                latestReason = LearningSkipReason.ALREADY_LEARNED;
                continue;
            }
            BedtimeExtractor.Result gate = BedtimeExtractor.extract(
                    night.intervals(), night.bedtimeStart(),
                    settings.getBedtimeHour(), settings.getBedtimeMinute(), date, zone);
            if (!gate.isOk()) {
                latestReason = LearningSkipReason.from(gate.failure());
                continue;
            }

            // 순차 prior: 직전 night가 갱신한 settings 값을 그대로 사용 (#1)
            double priorMean = settings.getLearnedMean();
            double priorVar = settings.getLearnedVariance();
            double multiplier = CaffeineCondition.fromRawValue(settings.getCondition()).multiplier();
            if (!(priorMean > 0 && priorVar > 0 && multiplier > 0)) {
                latestReason = LearningSkipReason.INVALID_PRIOR;
                continue;
            }

            Instant bedtime = gate.bedtime();
            List<Dose> doses = caffeineRepository
                    .findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                            userId, bedtime.minus(Duration.ofHours((long) BayesianHalfLifeUpdater.DOSES_LOOKBACK_HOURS)), bedtime)
                    .stream().map(r -> new Dose(r.getTimestamp(), r.getAmount())).toList();

            Linearization lin = BayesianHalfLifeUpdater.linearize(bedtime, doses, priorMean, multiplier);
            if (lin == null) {
                latestReason = LearningSkipReason.INSUFFICIENT_CAFFEINE_RESIDUAL;
                continue;
            }
            Posterior raw = BayesianHalfLifeUpdater.posteriorEstimate(priorMean, priorVar, lin, gate.solMinutes());
            Posterior safe = BayesianHalfLifeUpdater.applySafetyBounds(raw.mean(), raw.variance(), priorMean);

            try {
                HalfLifeObservation obs = HalfLifeObservation.create(
                        userId, date, lin.cMu(), gate.solMinutes(),
                        priorMean, priorVar, safe.mean(), safe.variance(), multiplier);
                observationRepository.save(obs);
                settings.applyLearning(safe.mean(), safe.variance(), date); // 저장 성공 후에만 갱신
                updated++;
            } catch (IllegalArgumentException e) {
                latestReason = LearningSkipReason.INVALID_OBSERVATION;
            }
        }

        if (!anySleepData) {
            latestReason = LearningSkipReason.NO_SLEEP_SUMMARY;
        }
        return response(updated, updated == 0 ? latestReason : null);
    }

    @Transactional(readOnly = true)
    public List<ObservationResponse> observations(Long userId) {
        return observationRepository.findByUserIdOrderByDateAsc(userId)
                .stream().map(ObservationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public LearningDashboardResponse dashboard(Long userId) {
        List<HalfLifeObservation> obs = observationRepository.findByUserIdOrderByDateAsc(userId);
        int count = obs.size();

        LatestEstimate latest = null;
        if (count >= 1) {
            HalfLifeObservation last = obs.get(count - 1);
            double halfWidth = 1.96 * Math.sqrt(last.getPosteriorVariance());
            latest = new LatestEstimate(
                    last.getPosteriorMean(), last.getPosteriorVariance(),
                    last.getPosteriorMean() - halfWidth, last.getPosteriorMean() + halfWidth,
                    LearningStatsCalc.confidenceScore(last.getPosteriorVariance()));
        }

        List<Point> points = obs.stream()
                .map(o -> new Point(LearningStatsCalc.predictedSOL(o.getPredictedResidualAtBedtime()),
                        o.getObservedSolMinutes()))
                .toList();
        Range domain = LearningStatsCalc.calibrationDomain(points);
        Calibration calibration = new Calibration(
                points.stream().map(p -> new PointItem(p.predicted(), p.observed())).toList(),
                LearningStatsCalc.rSquared(points), LearningStatsCalc.rmse(points),
                domain.lower(), domain.upper());

        return new LearningDashboardResponse(
                count, latest,
                obs.stream().map(ObservationResponse::from).toList(),
                calibration,
                histogram(obs.stream().map(HalfLifeObservation::getPredictedResidualAtBedtime).toList()),
                histogram(obs.stream().map(HalfLifeObservation::getObservedSolMinutes).toList()));
    }

    private List<HistogramBinItem> histogram(List<Double> values) {
        return LearningStatsCalc.histogram(values, HISTOGRAM_BINS).stream()
                .map(b -> new HistogramBinItem(b.lower(), b.upper(), b.count()))
                .toList();
    }

    private LearningRunResponse response(int updated, LearningSkipReason reason) {
        return new LearningRunResponse(updated,
                reason == null ? null : reason.name(),
                reason == null ? null : reason.message());
    }
}
