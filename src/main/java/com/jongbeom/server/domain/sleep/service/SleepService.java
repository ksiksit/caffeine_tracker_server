package com.jongbeom.server.domain.sleep.service;

import com.jongbeom.server.domain.calc.time.LocalCalendar;
import com.jongbeom.server.domain.calc.sleep.SleepMerger;
import com.jongbeom.server.domain.calc.sleep.SleepMerger.Interval;
import com.jongbeom.server.domain.calc.sleep.SleepStage;
import com.jongbeom.server.domain.calc.sleep.SleepSummaryCalc;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse.RecordItem;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse.StageItem;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesRequest;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesRequest.SampleItem;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesResponse;
import com.jongbeom.server.domain.sleep.entity.SleepSample;
import com.jongbeom.server.domain.sleep.repository.SleepSampleRepository;
import com.jongbeom.server.domain.user.UserWriteLock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HealthKit 수면 샘플 업로드 + 병합·요약 조회. 병합 결과는 learning 도메인도 재사용한다. */
@Service
@RequiredArgsConstructor
public class SleepService {

    private final SleepSampleRepository sleepSampleRepository;
    private final UserWriteLock userWriteLock;

    /** 병합된 한 night(intervals + 원시 inBed bedtimeStart). summary·learning 이 공유하는 형태. */
    public record NightData(List<Interval> intervals, Instant bedtimeStart) {
        public boolean isEmpty() {
            return intervals.isEmpty();
        }
    }

    /**
     * 원시 샘플 멱등 업로드(client_uuid 중복은 건너뜀). 같은 배치가 동시에 오면(백그라운드 동기화 중복 등)
     * 둘 다 "미저장"으로 판단해 UNIQUE 를 위반하므로, 사용자 쓰기 잠금 뒤에 저장 여부를 확인한다.
     */
    @Transactional
    public UploadSleepSamplesResponse upload(Long userId, UploadSleepSamplesRequest request) {
        userWriteLock.lock(userId);
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

    /** date(아침 기준) 전날 18:00 ~ 당일 12:00 윈도우 샘플을 병합. */
    @Transactional(readOnly = true)
    public NightData mergedNight(Long userId, LocalDate date, ZoneId zone) {
        Instant start = LocalCalendar.sleepWindowStart(date, zone);
        Instant end = LocalCalendar.sleepWindowEnd(date, zone);
        return toNight(sleepSampleRepository
                .findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(userId, start, end));
    }

    /**
     * from~to(아침 날짜, 양끝 포함) 각 날짜의 병합 night. 샘플은 한 번에 조회하고 날짜별 윈도우로 나눈다 —
     * 결과는 날짜마다 {@link #mergedNight}를 부른 것과 같다. 데이터 없는 날짜는 빠지고, 날짜 오름차순.
     * public 인 이유: {@link com.jongbeom.server.domain.learning.service.LearningService}가 학습 입력으로 쓴다.
     */
    @Transactional(readOnly = true)
    public SortedMap<LocalDate, NightData> mergedNights(Long userId, LocalDate from, LocalDate to, ZoneId zone) {
        List<SleepSample> samples = sleepSampleRepository
                .findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        userId, LocalCalendar.sleepWindowStart(from, zone), LocalCalendar.sleepWindowEnd(to, zone));
        SortedMap<LocalDate, NightData> nights = new TreeMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant start = LocalCalendar.sleepWindowStart(date, zone);
            Instant end = LocalCalendar.sleepWindowEnd(date, zone);
            NightData night = toNight(samples.stream()
                    .filter(s -> !s.getStartAt().isBefore(start) && s.getStartAt().isBefore(end))
                    .toList());
            if (!night.isEmpty()) {
                nights.put(date, night);
            }
        }
        return nights;
    }

    /** 한 윈도우의 샘플(시작시각 오름차순) → 병합 night. inBed 최초 시작이 bedtimeStart. */
    private static NightData toNight(List<SleepSample> samples) {
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
