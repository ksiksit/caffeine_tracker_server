package com.jongbeom.server.global.error;

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

    public record FieldError(
            String field,
            String message
    ) {
    }
}
