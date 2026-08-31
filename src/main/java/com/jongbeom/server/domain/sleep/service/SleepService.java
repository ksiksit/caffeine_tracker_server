package com.jongbeom.server.domain.sleep.service;

import com.jongbeom.server.domain.calc.LocalCalendar;
import com.jongbeom.server.domain.calc.SleepMerger;
import com.jongbeom.server.domain.calc.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.SleepStage;
import com.jongbeom.server.domain.calc.SleepSummaryCalc;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse.RecordItem;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse.StageItem;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesRequest;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesRequest.SampleItem;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesResponse;
import com.jongbeom.server.domain.sleep.entity.SleepSample;
import com.jongbeom.server.domain.sleep.repository.SleepSampleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HealthKit 수면 샘플 업로드 + 병합·요약 조회. 병합 결과는 learning 도메인도 재사용한다. */
@Service
@RequiredArgsConstructor
public class SleepService {

    private final SleepSampleRepository sleepSampleRepository;

    /** 병합된 한 night(intervals + 원시 inBed bedtimeStart). summary·learning 이 공유하는 형태. */
    public record NightData(List<Interval> intervals, Instant bedtimeStart) {
        public boolean isEmpty() {
            return intervals.isEmpty();
        }
    }

    /** 원시 샘플 멱등 업로드(client_uuid 중복은 건너뜀). */
    @Transactional
    public UploadSleepSamplesResponse upload(Long userId, UploadSleepSamplesRequest request) {
        Map<String, SampleItem> byUuid = dedupeByUuid(request.samples());
        Set<String> alreadyStored = findAlreadyStoredUuids(userId, byUuid.keySet());

        List<SleepSample> toInsert = byUuid.values().stream()
                .filter(item -> !alreadyStored.contains(item.clientUuid()))
                .map(item -> SleepSample.create(
                        userId, item.clientUuid(),
                        item.start().toInstant(), item.end().toInstant(), item.hkValue()))
                .toList();

        sleepSampleRepository.saveAll(toInsert);
        return new UploadSleepSamplesResponse(request.samples().size(), toInsert.size());
    }

    /** 배치 내 client_uuid 중복 제거 — 첫 번째 항목 유지, 입력 순서 보존. */
    private static Map<String, SampleItem> dedupeByUuid(List<SampleItem> samples) {
        Map<String, SampleItem> byUuid = new LinkedHashMap<>();
        for (SampleItem item : samples) {
            byUuid.putIfAbsent(item.clientUuid(), item);
        }
        return byUuid;
    }

    /** 이미 저장된 client_uuid 집합. */
    private Set<String> findAlreadyStoredUuids(Long userId, Set<String> uuids) {
        return sleepSampleRepository
                .findByUserIdAndClientUuidIn(userId, uuids)
                .stream().map(SleepSample::getClientUuid).collect(Collectors.toSet());
    }

    /**
     * date(아침 기준) 전날 18:00 ~ 당일 12:00 윈도우 샘플을 병합.
     * public 인 이유: {@link com.jongbeom.server.domain.learning.service.LearningService}가 학습 입력으로 재사용한다.
     */
    @Transactional(readOnly = true)
    public NightData mergedNight(Long userId, LocalDate date, ZoneId zone) {
        Instant start = LocalCalendar.sleepWindowStart(date, zone);
        Instant end = LocalCalendar.sleepWindowEnd(date, zone);

        List<SleepSample> samples = sleepSampleRepository
                .findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(userId, start, end);
        if (samples.isEmpty()) {
            return new NightData(List.of(), null);
        }

        Instant bedtimeStart = samples.stream()
                .filter(s -> s.getHkValue() == SleepStage.IN_BED.hkValue())
                .map(SleepSample::getStartAt)
                .min(Instant::compareTo)
                .orElse(null);

        List<SleepMerger.Sample> input = samples.stream()
                .map(s -> new SleepMerger.Sample(s.getStartAt(), s.getEndAt(), s.getHkValue()))
                .toList();
        return new NightData(SleepMerger.merge(input), bedtimeStart);
    }

    /** date(아침 기준) 윈도우 샘플을 병합·계산한 요약. */
    @Transactional(readOnly = true)
    public SleepSummaryResponse summary(Long userId, LocalDate date, ZoneId zone) {
        NightData night = mergedNight(userId, date, zone);
        if (night.isEmpty()) {
            return SleepSummaryResponse.empty(date);
        }
        SleepSummaryCalc.Result computed = SleepSummaryCalc.compute(night.intervals(), night.bedtimeStart());

        List<RecordItem> records = night.intervals().stream()
                .map(interval -> new RecordItem(interval.start(), interval.end(), interval.hkValue()))
                .toList();
        List<StageItem> breakdown = computed.stageBreakdown().stream()
                .map(stage -> new StageItem(stage.stage().hkValue(), stage.durationSeconds()))
                .toList();

        return new SleepSummaryResponse(
                date, true, night.bedtimeStart(), records,
                computed.totalSleepSeconds(), computed.timeInBedSeconds(), computed.sleepEfficiency(),
                computed.sleepOnsetLatencySeconds(), breakdown);
    }
}
