package com.jongbeom.server.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 코드 — HTTP 상태와 클라이언트 응답 메시지의 단일 출처.
 * 예외 클래스가 아니라 여기의 메시지가 응답 본문에 나간다.
 */
public enum ErrorCode {
    // 인증
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "RefreshToken 이 유효하지 않습니다."),
    // 공통(요청 형식)
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "잘못된 요청 형식입니다."),
    // 카페인
    CAFFEINE_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "카페인 기록을 찾을 수 없습니다."),
    // 공통(서버)
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
