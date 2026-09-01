package com.jongbeom.server.domain.caffeine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record CreateCaffeineRecordRequest(
        @NotNull @Min(1) @Max(10000) Integer amount,
        @NotBlank @Size(max = 100) String drinkName,
        @NotNull OffsetDateTime timestamp
) {
}
