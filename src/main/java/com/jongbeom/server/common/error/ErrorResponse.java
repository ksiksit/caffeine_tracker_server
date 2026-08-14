package com.jongbeom.server.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "에러 응답 공통 포맷")
public record ErrorResponse(
        @Schema(description = "에러 코드 — ErrorCode enum 이름, 또는 프레임워크 발생 에러의 HTTP_{status}",
                example = "INVALID_CREDENTIALS")
        String code,

        @Schema(description = "사람이 읽을 수 있는 에러 메시지", example = "이메일 또는 비밀번호가 올바르지 않습니다.")
        String message,

        @Schema(description = "필드별 검증 오류 목록 (VALIDATION_FAILED 일 때만 채워짐)")
        List<FieldError> fieldErrors,

        @Schema(description = "에러 발생 시각 (ISO-8601 with offset, 서버는 UTC)", example = "2026-05-02T10:30:00Z")
        OffsetDateTime timestamp
) {
    public static ErrorResponse of(ErrorCode code) {
        return of(code, code.message(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, null);
    }

    public static ErrorResponse of(ErrorCode code, List<FieldError> fieldErrors) {
        return of(code, code.message(), fieldErrors);
    }

    private static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code.name(), message, fieldErrors, OffsetDateTime.now());
    }

    @Schema(description = "필드별 검증 오류")
    public record FieldError(
            @Schema(description = "오류 발생 필드명", example = "password") String field,
            @Schema(description = "필드별 오류 메시지", example = "8자 이상 입력해주세요") String message
    ) {
    }
}
