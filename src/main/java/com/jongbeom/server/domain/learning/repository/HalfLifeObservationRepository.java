package com.jongbeom.server.domain.learning.repository;

import com.jongbeom.server.domain.learning.entity.HalfLifeObservation;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HalfLifeObservationRepository extends JpaRepository<HalfLifeObservation, Long> {

    boolean existsByUserIdAndDate(Long userId, LocalDate date);

    List<HalfLifeObservation> findByUserIdOrderByDateAsc(Long userId);
}
