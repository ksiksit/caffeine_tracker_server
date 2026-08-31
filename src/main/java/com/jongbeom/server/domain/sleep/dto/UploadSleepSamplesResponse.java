package com.jongbeom.server.domain.sleep.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "수면 샘플 업로드 결과")
public record UploadSleepSamplesResponse(
        @Schema(description = "수신한 샘플 수", example = "120") int received,
        @Schema(description = "새로 저장된 샘플 수(멱등 중복 제외)", example = "118") int inserted
) {
}
