package com.jongbeom.server.global.web;

import com.jongbeom.server.global.error.ApiError;
import com.jongbeom.server.global.error.ErrorCode;
import java.util.List;

/**
 * 모든 API 응답의 공통 봉투. 키 4개는 null 이어도 항상 직렬화된다(클라이언트가 형태를 고정 가정).
 * <ul>
 *   <li>{@code success} — 성공 여부. HTTP 상태와 중복이지만 바디만으로 분기할 수 있게 한다</li>
 *   <li>{@code data} — 성공 페이로드. 실패 시 항상 null</li>
 *   <li>{@code error} — 기계용 코드·상세({@link ApiError}). 성공 시 null</li>
 *   <li>{@code message} — 사람에게 보여줄 문장. 실패 시 {@link ErrorCode#message()}, 성공 시 null</li>
 * </ul>
 * 성공은 컨트롤러가 {@link #ok}로 만들고, 실패는 {@code GlobalExceptionHandler}·{@code JsonSecurityErrorHandler}만
 * {@link #error}로 만든다. 상세 계약은 README "공통 응답 구조".
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /** 바디 없는 성공(구 204 대체) — 200 + {@code data: null}. */
    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return error(ApiError.of(code), code.message());
    }

    public static ApiResponse<Void> error(ErrorCode code, List<ApiError.FieldError> fieldErrors) {
        return error(ApiError.of(code, fieldErrors), code.message());
    }

    /** {@link ErrorCode}에 없는 코드가 필요한 경우(프레임워크가 상태를 정하는 {@code HTTP_{status}})용. */
    public static ApiResponse<Void> error(ApiError error, String message) {
        return new ApiResponse<>(false, null, error, message);
    }
}
