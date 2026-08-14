package com.jongbeom.server.caffeine.service;

import com.jongbeom.server.caffeine.dto.CaffeineRecordResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse.CutoffResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse.DataPointResponse;
import com.jongbeom.server.caffeine.dto.CreateCaffeineRecordRequest;
import com.jongbeom.server.caffeine.dto.UpdateCaffeineRecordRequest;
import com.jongbeom.server.caffeine.entity.CaffeineRecord;
import com.jongbeom.server.caffeine.exception.CaffeineRecordNotFoundException;
import com.jongbeom.server.caffeine.repository.CaffeineRecordRepository;
import com.jongbeom.server.calc.LocalCalendar;
import com.jongbeom.server.calc.Pharmacokinetics;
import com.jongbeom.server.calc.Pharmacokinetics.CutoffResult;
import com.jongbeom.server.calc.Pharmacokinetics.Dose;
import com.jongbeom.server.settings.entity.UserSettings;
import com.jongbeom.server.settings.service.UserSettingsService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카페인 기록 CRUD + 오늘 현황(차트·잔량·마감시각) 서버 계산. */
@Service
@RequiredArgsConstructor
public class CaffeineService {

    private final CaffeineRecordRepository caffeineRecordRepository;
    private final UserSettingsService settingsService;

    @Transactional
    public CaffeineRecordResponse create(Long userId, CreateCaffeineRecordRequest request) {
        CaffeineRecord saved = caffeineRecordRepository.save(CaffeineRecord.create(
                userId, request.amount(), request.drinkName(), request.timestamp().toInstant()));
        return CaffeineRecordResponse.from(saved);
    }

    @Transactional
    public CaffeineRecordResponse update(Long userId, Long id, UpdateCaffeineRecordRequest request) {
        CaffeineRecord record = caffeineRecordRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CaffeineRecordNotFoundException(id));
        record.update(request.amount(), request.drinkName(), request.timestamp().toInstant());
        return CaffeineRecordResponse.from(record);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        CaffeineRecord record = caffeineRecordRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CaffeineRecordNotFoundException(id));
        caffeineRecordRepository.delete(record);
    }

    @Transactional(readOnly = true)
    public List<CaffeineRecordResponse> listToday(Long userId, Instant now, ZoneId zone) {
        Instant chartStart = LocalCalendar.chartStart(now, zone);
        return caffeineRecordRepository
                .findByUserIdAndTimestampGreaterThanEqualOrderByTimestampAsc(userId, chartStart)
                .stream().map(CaffeineRecordResponse::from).toList();
    }

    /**
     * 홈 화면용 통합 계산: settings 를 읽어 차트 시계열·현재/취침 잔량·마감시각을 한 번에 산출.
     * 오늘 기록이 없으면 차트는 빈 배열·잔량 0이고 마감시각만 계산된다.
     * (← iOS HomeViewModel.updateChartData 를 서버로 이관)
     */
    @Transactional(readOnly = true)
    public CaffeineTodayResponse today(Long userId, Instant now, ZoneId zone) {
        UserSettings settings = settingsService.getOrCreate(userId);
        // 엔티티 필드 halfLife(슬라이더 값)와 다른 개념 — 학습값·건강상태 배수가 반영된 유효 반감기
        double effectiveHalfLifeHours = settings.effectiveHalfLifeHours();
        int referenceDoseMg = settings.getReferenceDoseMg();

        Instant chartStart = LocalCalendar.chartStart(now, zone);
        Instant chartEnd = LocalCalendar.chartEnd(now, zone);
        Instant bedtime = LocalCalendar.nextBedtime(
                now, zone, settings.getBedtimeHour(), settings.getBedtimeMinute());

        List<CaffeineRecord> todayRecords = caffeineRecordRepository
                .findByUserIdAndTimestampGreaterThanEqualOrderByTimestampAsc(userId, chartStart);
        List<Dose> doses = todayRecords.stream()
                .map(r -> new Dose(r.getTimestamp(), r.getAmount()))
                .toList();

        ChartAndResiduals computed = computeChartAndResiduals(
                doses, chartStart, chartEnd, now, bedtime, effectiveHalfLifeHours);
        CutoffResult cutoff = Pharmacokinetics.cutoffTime(
                doses, bedtime, referenceDoseMg, effectiveHalfLifeHours,
                Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG);

        List<CaffeineRecordResponse> records = todayRecords.stream()
                .map(CaffeineRecordResponse::from).toList();
        int todayTotal = todayRecords.stream().mapToInt(CaffeineRecord::getAmount).sum();
        boolean overDailyLimit = todayTotal > Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG;

        return new CaffeineTodayResponse(
                now, bedtime,
                records,
                todayTotal, overDailyLimit,
                computed.chart(), computed.currentResidual(), computed.predictedAtBedtime(),
                toCutoffResponse(cutoff), effectiveHalfLifeHours, referenceDoseMg);
    }

    /** 차트 시계열 + 현재/취침 잔량 묶음. */
    private record ChartAndResiduals(
            List<DataPointResponse> chart, double currentResidual, double predictedAtBedtime) {
    }

    /** doses 가 비면 iOS와 동일하게 빈 차트·잔량 0. */
    private static ChartAndResiduals computeChartAndResiduals(
            List<Dose> doses, Instant chartStart, Instant chartEnd,
            Instant now, Instant bedtime, double effectiveHalfLifeHours) {
        if (doses.isEmpty()) {
            return new ChartAndResiduals(List.of(), 0, 0);
        }
        List<DataPointResponse> chart = Pharmacokinetics
                .generateTimeSeries(doses, chartStart, chartEnd,
                        Pharmacokinetics.CHART_INTERVAL_MINUTES, effectiveHalfLifeHours)
                .stream().map(p -> new DataPointResponse(p.time(), p.concentrationMg())).toList();
        double currentResidual = Pharmacokinetics.totalRemaining(now, doses, effectiveHalfLifeHours);
        double predictedAtBedtime = Pharmacokinetics.totalRemaining(bedtime, doses, effectiveHalfLifeHours);
        return new ChartAndResiduals(chart, currentResidual, predictedAtBedtime);
    }

    /** 상태별로 유효한 필드만 채운다 — cutoff 는 CUTOFF, existingAtBedtime 은 ALREADY_EXCEEDED 전용. */
    private CutoffResponse toCutoffResponse(CutoffResult result) {
        return switch (result.status()) {
            case SAFE_ANYTIME -> new CutoffResponse(result.status().name(), null, null);
            case CUTOFF -> new CutoffResponse(result.status().name(), result.cutoff(), null);
            case ALREADY_EXCEEDED -> new CutoffResponse(result.status().name(), null, result.existingAtBedtime());
        };
    }
}
