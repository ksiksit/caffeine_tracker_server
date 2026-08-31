package com.jongbeom.server.domain.caffeine.entity;

import com.jongbeom.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 카페인 섭취 기록. iOS SwiftData {@code CaffeineRecord} 이관(amount 는 INT mg 유지). */
@Entity
@Getter
@Table(name = "caffeine_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaffeineRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int amount;

    @Column(name = "drink_name", nullable = false, length = 100)
    private String drinkName;

    /** 섭취 시각(UTC 절대시각). */
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    private CaffeineRecord(Long userId, int amount, String drinkName, Instant timestamp) {
        this.userId = userId;
        this.amount = amount;
        this.drinkName = drinkName;
        this.timestamp = timestamp;
    }

    public static CaffeineRecord create(Long userId, int amount, String drinkName, Instant timestamp) {
        return new CaffeineRecord(userId, amount, drinkName, timestamp);
    }

    public void update(int amount, String drinkName, Instant timestamp) {
        this.amount = amount;
        this.drinkName = drinkName;
        this.timestamp = timestamp;
    }
}
