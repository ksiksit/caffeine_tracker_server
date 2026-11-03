package com.jongbeom.server.domain.learning.repository;

import com.jongbeom.server.domain.learning.entity.HalfLifeObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HalfLifeObservationRepository extends JpaRepository<HalfLifeObservation, Long> {

    /** from~to(양끝 포함) 중 이미 관측이 저장된 날짜. 학습 실행이 날짜마다 존재 여부를 따로 묻지 않도록 한 번에 읽는다. */
    @Query("select o.date from HalfLifeObservation o where o.userId = :userId and o.date between :from and :to")
    Set<LocalDate> findDatesByUserIdAndDateBetween(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<HalfLifeObservation> findByUserIdOrderByDateAsc(Long userId);
}
