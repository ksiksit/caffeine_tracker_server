package com.jongbeom.server.sleep.repository;

import com.jongbeom.server.sleep.entity.SleepSample;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SleepSampleRepository extends JpaRepository<SleepSample, Long> {

    List<SleepSample> findByUserIdAndClientUuidIn(Long userId, Collection<String> clientUuids);

    /** [start, end) 윈도우의 샘플을 시작시각 오름차순으로. */
    List<SleepSample> findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
            Long userId, Instant start, Instant end);
}
