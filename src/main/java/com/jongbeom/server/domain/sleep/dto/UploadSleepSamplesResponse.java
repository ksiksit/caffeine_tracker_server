package com.jongbeom.server.domain.sleep.dto;

public record UploadSleepSamplesResponse(
        int received,
        int inserted
) {
}
