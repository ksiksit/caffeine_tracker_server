package com.jongbeom.server.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 응답 봉투({@code ApiResponse})의 {@code error} 객체 — 기계용 정보만 담는다(사람용 문장은 봉투의 {@code message}).
 * {@code fieldErrors}는 검증 실패(VALIDATION_FAILED)에만 있고, 없으면 키 자체를 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, List<FieldError> fieldErrors) {

    public static ApiError of(ErrorCode code) {
        return new ApiError(code.name(), null);
    }

    public static ApiError of(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiError(code.name(), fieldErrors);
    }

    public record FieldError(String field, String message) {
    }
}
