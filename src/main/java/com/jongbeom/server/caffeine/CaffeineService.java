package com.jongbeom.server.caffeine;

import com.jongbeom.server.caffeine.dto.CaffeineRecordResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse.CutoffResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse.DataPointResponse;
import com.jongbeom.server.caffeine.dto.CreateCaffeineRecordRequest;
import com.jongbeom.server.caffeine.dto.UpdateCaffeineRecordRequest;
import com.jongbeom.server.caffeine.exception.CaffeineRecordNotFoundException;
import com.jongbeom.server.calc.LocalCalendar;
import com.jongbeom.server.calc.Pharmacokinetics;
import com.jongbeom.server.calc.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import com.jongbeom.server.settings.UserSettings;
import com.jongbeom.server.settings.UserSettingsService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaffeineService {

    private final CaffeineRecordRepository repository;
    private final UserSettingsService settingsService;

    @Transactional
    public CaffeineRecordResponse create(Long userId, CreateCaffeineRecordRequest req) {
        CaffeineRecord saved = repository.save(CaffeineRecord.create(
                userId, req.amount(), req.drinkName(), req.timestamp().toInstant()));
        return CaffeineRecordResponse.from(saved);
    }

    @Transactional
    public CaffeineRecordResponse update(Long userId, Long id, UpdateCaffeineRecordRequest req) {
        CaffeineRecord record = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CaffeineRecordNotFoundException(id));
        record.update(req.amount(), req.drinkName(), req.timestamp().toInstant());
        return CaffeineRecordResponse.from(record);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        CaffeineRecord record = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CaffeineRecordNotFoundException(id));
        repository.delete(record);
    }

    @Transactional(readOnly = true)
    public List<CaffeineRecordResponse> listToday(Long userId, Instant now, ZoneId zone) {
        Instant chartStart = LocalCalendar.chartStart(now, zone);
        return repository
                .findByUserIdAndTimestampGreaterThanEqualOrderByTimestampAsc(userId, chartStart)
                .stream().map(CaffeineRecordResponse::from).toList();
    }

    /**
     * 홈 화면용 통합 계산: settings 를 읽어 차트 시계열·현재/취침 잔량·마감시각을 한 번에 산출.
     * (← iOS HomeViewModel.updateChartData 를 서버로 이관)
     */
    @Transactional(readOnly = true)
    public CaffeineTodayResponse today(Long userId, Instant now, ZoneId zone) {
        UserSettings settings = settingsService.getOrCreate(userId);
        double halfLife = settings.effectiveHalfLifeHours();
        int referenceDoseMg = settings.getReferenceDoseMg();

        Instant chartStart = LocalCalendar.chartStart(now, zone);
        Instant chartEnd = LocalCalendar.chartEnd(now, zone);
        Instant bedtime = LocalCalendar.nextBedtime(now, zone, settings.getBedtimeHour(), settings.getBedtimeMinute());

        List<CaffeineRecord> todayRecords = repository
                .findByUserIdAndTimestampGreaterThanEqualOrderByTimestampAsc(userId, chartStart);
        List<Dose> doses = todayRecords.stream()
                .map(r -> new Dose(r.getTimestamp(), r.getAmount()))
                .toList();

        int todayTotal = todayRecords.stream().mapToInt(CaffeineRecord::getAmount).sum();

        List<DataPointResponse> chart;
        double currentResidual;
        double predictedAtBedtime;
        if (doses.isEmpty()) {
            // iOS: doses 비면 차트는 빈 배열, 잔량 0 (마감시각만 계산)
            chart = List.of();
            currentResidual = 0;
            predictedAtBedtime = 0;
        } else {
            chart = Pharmacokinetics
                    .generateTimeSeries(doses, chartStart, chartEnd, Pharmacokinetics.CHART_INTERVAL_MINUTES, halfLife)
                    .stream().map(p -> new DataPointResponse(p.time(), p.concentrationMg())).toList();
            currentResidual = Pharmacokinetics.totalRemaining(now, doses, halfLife);
            predictedAtBedtime = Pharmacokinetics.totalRemaining(bedtime, doses, halfLife);
        }

        CutoffResult cutoff = Pharmacokinetics.cutoffTime(
                doses, bedtime, referenceDoseMg, halfLife, Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG);

        return new CaffeineTodayResponse(
                now, bedtime,
                todayRecords.stream().map(CaffeineRecordResponse::from).toList(),
                todayTotal, todayTotal > Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG,
                chart, currentResidual, predictedAtBedtime,
                toCutoffResponse(cutoff), halfLife, referenceDoseMg);
    }

    private CutoffResponse toCutoffResponse(CutoffResult r) {
        return new CutoffResponse(
                r.status().name(),
                r.status() == CutoffResult.Status.CUTOFF ? r.cutoff() : null,
                r.status() == CutoffResult.Status.ALREADY_EXCEEDED ? r.existingAtBedtime() : null);
    }
}
