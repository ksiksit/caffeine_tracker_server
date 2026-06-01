package com.jongbeom.server.sleep;

import com.jongbeom.server.calc.LocalCalendar;
import com.jongbeom.server.calc.SleepMerger;
import com.jongbeom.server.calc.SleepMerger.Interval;
import com.jongbeom.server.calc.SleepSummaryCalc;
import com.jongbeom.server.sleep.dto.SleepSummaryResponse;
import com.jongbeom.server.sleep.dto.SleepSummaryResponse.RecordItem;
import com.jongbeom.server.sleep.dto.SleepSummaryResponse.StageItem;
import com.jongbeom.server.sleep.dto.UploadSleepSamplesRequest;
import com.jongbeom.server.sleep.dto.UploadSleepSamplesResponse;
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

@Service
@RequiredArgsConstructor
public class SleepService {

    private final SleepSampleRepository repository;

    /** 원시 샘플 멱등 업로드(client_uuid 중복은 건너뜀). */
    @Transactional
    public UploadSleepSamplesResponse upload(Long userId, UploadSleepSamplesRequest req) {
        // 배치 내 client_uuid 중복 제거(첫 번째 유지)
        Map<String, UploadSleepSamplesRequest.SampleItem> byUuid = new LinkedHashMap<>();
        for (UploadSleepSamplesRequest.SampleItem item : req.samples()) {
            byUuid.putIfAbsent(item.clientUuid(), item);
        }

        Set<String> existing = repository
                .findByUserIdAndClientUuidIn(userId, byUuid.keySet())
                .stream().map(SleepSample::getClientUuid).collect(Collectors.toSet());

        List<SleepSample> toInsert = byUuid.values().stream()
                .filter(item -> !existing.contains(item.clientUuid()))
                .map(item -> SleepSample.create(
                        userId, item.clientUuid(),
                        item.start().toInstant(), item.end().toInstant(), item.hkValue()))
                .toList();

        repository.saveAll(toInsert);
        return new UploadSleepSamplesResponse(req.samples().size(), toInsert.size());
    }

    /** 병합된 한 night(intervals + 원시 inBed bedtimeStart). summary·learning 공유. */
    public record NightData(List<Interval> intervals, Instant bedtimeStart) {
        public boolean isEmpty() {
            return intervals.isEmpty();
        }
    }

    /** date(아침 기준) 전날 18:00 ~ 당일 12:00 윈도우 샘플을 병합. */
    @Transactional(readOnly = true)
    public NightData mergedNight(Long userId, LocalDate date, ZoneId zone) {
        Instant start = LocalCalendar.sleepWindowStart(date, zone);
        Instant end = LocalCalendar.sleepWindowEnd(date, zone);

        List<SleepSample> samples = repository
                .findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(userId, start, end);
        if (samples.isEmpty()) {
            return new NightData(List.of(), null);
        }

        Instant bedtimeStart = samples.stream()
                .filter(s -> s.getHkValue() == 0) // inBed
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
        Instant bedtimeStart = night.bedtimeStart();
        List<Interval> intervals = night.intervals();
        SleepSummaryCalc.Result res = SleepSummaryCalc.compute(intervals, bedtimeStart);

        List<RecordItem> records = intervals.stream()
                .map(iv -> new RecordItem(iv.start(), iv.end(), iv.hkValue()))
                .toList();
        List<StageItem> breakdown = res.stageBreakdown().stream()
                .map(sd -> new StageItem(sd.stage().hkValue(), sd.durationSeconds()))
                .toList();

        return new SleepSummaryResponse(
                date, true, bedtimeStart, records,
                res.totalSleepSeconds(), res.timeInBedSeconds(), res.sleepEfficiency(),
                res.sleepOnsetLatencySeconds(), breakdown);
    }
}
