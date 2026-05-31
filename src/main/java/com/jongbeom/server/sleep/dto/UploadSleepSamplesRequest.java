package com.jongbeom.server.sleep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "HealthKit 원시 수면 샘플 업로드(배치, client_uuid 멱등)")
public record UploadSleepSamplesRequest(
        @Schema(description = "수면 샘플 목록")
        @NotEmpty @Valid List<SampleItem> samples
) {
    @Schema(description = "수면 샘플 한 건")
    public record SampleItem(
            @Schema(description = "클라이언트 생성 멱등 키", example = "B1F1...")
            @NotBlank @Size(max = 64) String clientUuid,

            @Schema(description = "시작 시각(ISO-8601+오프셋)", example = "2026-05-31T23:10:00+09:00")
            @NotNull OffsetDateTime start,

            @Schema(description = "종료 시각(ISO-8601+오프셋)", example = "2026-05-31T23:40:00+09:00")
            @NotNull OffsetDateTime end,

            @Schema(description = "HK rawValue(0=inBed,1=unspecified,2=awake,3=core,4=deep,5=rem)", example = "3")
            @NotNull @Min(0) @Max(5) Integer hkValue
    ) {
    }
}
