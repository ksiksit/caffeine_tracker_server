package com.jongbeom.server.caffeine.repository;

import com.jongbeom.server.caffeine.entity.CaffeineRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaffeineRecordRepository extends JpaRepository<CaffeineRecord, Long> {

    Optional<CaffeineRecord> findByIdAndUserId(Long id, Long userId);

    /** [start, ∞) 기록을 시간 오름차순으로. 오늘 기록(차트 시작 5시 이후) 조회용. */
    List<CaffeineRecord> findByUserIdAndTimestampGreaterThanEqualOrderByTimestampAsc(
            Long userId, Instant start);

    /** [start, end) 구간. */
    List<CaffeineRecord> findByUserIdAndTimestampBetweenOrderByTimestampAsc(
            Long userId, Instant start, Instant end);
}
