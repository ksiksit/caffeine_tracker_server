package com.jongbeom.server.common.error;

import com.jongbeom.server.auth.exception.DuplicateEmailException;
import com.jongbeom.server.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.caffeine.exception.CaffeineRecordNotFoundException;
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

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        ErrorCode code = ErrorCode.EMAIL_ALREADY_EXISTS;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        ErrorCode code = ErrorCode.INVALID_CREDENTIALS;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        ErrorCode code = ErrorCode.INVALID_REFRESH_TOKEN;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(CaffeineRecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCaffeineRecordNotFound(CaffeineRecordNotFoundException e) {
        ErrorCode code = ErrorCode.CAFFEINE_RECORD_NOT_FOUND;
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
        return ResponseEntity.status(status).headers(headers).body(
                new ErrorResponse(
                        "HTTP_" + status.value(),
                        ex.getMessage(),
                        null,
                        java.time.OffsetDateTime.now()));
    }
}
