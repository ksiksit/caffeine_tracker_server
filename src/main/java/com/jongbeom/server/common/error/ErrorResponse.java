package com.jongbeom.server.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
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

    public record FieldError(String field, String message) {
    }
}
