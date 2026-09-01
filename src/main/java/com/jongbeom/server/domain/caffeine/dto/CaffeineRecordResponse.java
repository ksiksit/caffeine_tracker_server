package com.jongbeom.server.domain.caffeine.dto;

import com.jongbeom.server.domain.caffeine.entity.CaffeineRecord;
import java.time.Instant;

public record CaffeineRecordResponse(
        Long id,
        int amount,
        String drinkName,
        Instant timestamp
) {
    public static CaffeineRecordResponse from(CaffeineRecord r) {
        return new CaffeineRecordResponse(r.getId(), r.getAmount(), r.getDrinkName(), r.getTimestamp());
    }
}
