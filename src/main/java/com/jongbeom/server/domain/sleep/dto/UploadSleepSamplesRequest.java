package com.jongbeom.server.domain.sleep.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public record UploadSleepSamplesRequest(
        @NotEmpty @Valid List<SampleItem> samples
) {
    public record SampleItem(
            @NotBlank @Size(max = 64) String clientUuid,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotNull @Min(0) @Max(5) Integer hkValue
    ) {
    }
}
