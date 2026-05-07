package com.jongbeom.server.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "에러 응답 공통 포맷")
public record ErrorResponse(
        @Schema(description = "에러 코드 (EMAIL_ALREADY_EXISTS, INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, VALIDATION_FAILED, MALFORMED_JSON, INTERNAL_ERROR 중 하나)",
                example = "INVALID_CREDENTIALS")
        String code,

        @Schema(description = "사람이 읽을 수 있는 에러 메시지", example = "이메일 또는 비밀번호가 올바르지 않습니다.")
        String message,

        @Schema(description = "필드별 검증 오류 목록 (VALIDATION_FAILED 일 때만 채워짐)")
        List<FieldError> fieldErrors,

        @Schema(description = "에러 발생 시각 (ISO-8601 with offset)", example = "2026-05-02T10:30:00+09:00")
        OffsetDateTime timestamp
) {
    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.message(), null, OffsetDateTime.now());
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null, OffsetDateTime.now());
    }

    public static ErrorResponse of(ErrorCode code, List<FieldError> fieldErrors) {
        return new ErrorResponse(code.name(), code.message(), fieldErrors, OffsetDateTime.now());
    }

    @Schema(description = "필드별 검증 오류")
    public record FieldError(
            @Schema(description = "오류 발생 필드명", example = "password") String field,
            @Schema(description = "필드별 오류 메시지", example = "8자 이상 입력해주세요") String message
    ) {
    }
}
