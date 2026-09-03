package com.jongbeom.server.global.error;

import com.jongbeom.server.global.web.ApiResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 → {@link ApiResponse} 실패 봉투. 컨트롤러 경로의 모든 에러가 여기를 지난다.
 *
 * <p>예외 경로별 코드:
 * <ul>
 *   <li>{@link BusinessException} — 예외가 가진 {@link ErrorCode} 그대로</li>
 *   <li>{@code @Valid} 실패 — VALIDATION_FAILED + fieldErrors, JSON 파싱 실패 — MALFORMED_JSON</li>
 *   <li>그 외 {@link ResponseEntityExceptionHandler} 훅(파라미터 누락·타입 불일치·404·405·415 등) —
 *       {@link #handleExceptionInternal}에서 프레임워크가 정한 HTTP 상태로 코드를 고른다(ProblemDetail 미사용)</li>
 *   <li>나머지 — INTERNAL_ERROR(500)</li>
 * </ul>
 * 시큐리티 필터 단계의 401/403은 컨트롤러 이전이라 여기를 거치지 않고 {@link JsonSecurityErrorHandler}가 같은 봉투를 쓴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 도메인 비즈니스 예외 일괄 처리. 상태·코드·메시지는 {@link ErrorCode}가 결정한다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.debug("비즈니스 예외: {}", e.getMessage()); // 맥락(이메일·id 등)은 로그로만
        return respond(e.getErrorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, fieldErrors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ErrorCode code = ErrorCode.MALFORMED_JSON;
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code));
    }

    /**
     * 위에서 따로 다루지 않은 프레임워크 예외의 공통 출구 — 부모가 만들던 ProblemDetail 대신 봉투를 바디로 넘긴다.
     * 상태 코드와 헤더(405 의 Allow 등)는 프레임워크가 정한 값을 그대로 쓴다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        log.debug("프레임워크 예외({}): {}", status.value(), ex.getMessage());
        return super.handleExceptionInternal(ex, envelopeFor(status), headers, status, request);
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code));
    }

    private static ApiResponse<Void> envelopeFor(HttpStatusCode status) {
        ErrorCode code = switch (status.value()) {
            case 400 -> ErrorCode.INVALID_PARAMETER;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : null;
        };
        if (code != null) {
            return ApiResponse.error(code);
        }
        // enum 에 없는 상태(406 등) — "HTTP_{status}" 코드와 표준 reason phrase
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String message = resolved != null ? resolved.getReasonPhrase() : "HTTP " + status.value();
        return ApiResponse.error(new ApiError("HTTP_" + status.value(), null), message);
    }
}
