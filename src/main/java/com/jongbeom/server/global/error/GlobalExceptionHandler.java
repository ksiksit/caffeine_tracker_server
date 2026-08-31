package com.jongbeom.server.global.error;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 → {@link ErrorResponse} 변환.
 *
 * <p>모든 에러 경로가 ErrorResponse 형태인 것은 아니다:
 * <ul>
 *   <li>여기서 오버라이드하지 않은 {@link ResponseEntityExceptionHandler} 훅
 *       (method-not-supported, missing-param 등)은 Spring 기본 RFC-7807 ProblemDetail 로 나간다.</li>
 *   <li>Security 필터 단계의 401은 빈 바디다 (엔트리포인트 미설정 — 수정은 동작 변경이라 보류).</li>
 *   <li>{@link #handleErrorResponseException}은 enum에 없는 {@code HTTP_{status}} 계열 코드를 쓴다.</li>
 * </ul>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE) // springdoc 등 다른 advice보다 먼저 잡아 ErrorResponse 포맷 보장
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 도메인 비즈니스 예외 일괄 처리. 상태·응답 메시지는 {@link ErrorCode}가 결정한다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.debug("비즈니스 예외: {}", e.getMessage()); // 맥락(이메일·id 등)은 로그로만
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, fieldErrors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ErrorCode code = ErrorCode.MALFORMED_JSON;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            ErrorResponseException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // 프레임워크가 상태를 정하는 경우 — ErrorCode enum에 없는 "HTTP_{status}" 코드로 내려간다.
        return ResponseEntity.status(status).headers(headers).body(
                new ErrorResponse(
                        "HTTP_" + status.value(),
                        ex.getMessage(),
                        null,
                        OffsetDateTime.now()));
    }
}
