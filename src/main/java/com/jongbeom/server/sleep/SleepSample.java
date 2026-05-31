package com.jongbeom.server.sleep;

import com.jongbeom.server.common.BaseTimeEntity;
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

/** HealthKit 원시 수면 샘플. 기기에서 읽어 업로드한 (start, end, hkValue). client_uuid 로 멱등. */
@Entity
@Getter
@Table(name = "sleep_samples")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SleepSample extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_uuid", nullable = false, length = 64)
    private String clientUuid;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /** HKCategoryValueSleepAnalysis rawValue (0=inBed,1=unspecified,2=awake,3=core,4=deep,5=rem). */
    @Column(name = "hk_value", nullable = false)
    private int hkValue;

    private SleepSample(Long userId, String clientUuid, Instant startAt, Instant endAt, int hkValue) {
        this.userId = userId;
        this.clientUuid = clientUuid;
        this.startAt = startAt;
        this.endAt = endAt;
        this.hkValue = hkValue;
    }

    public static SleepSample create(Long userId, String clientUuid, Instant startAt, Instant endAt, int hkValue) {
        return new SleepSample(userId, clientUuid, startAt, endAt, hkValue);
    }
}
