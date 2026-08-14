package com.jongbeom.server.common.error;

import lombok.Getter;

/**
 * 도메인 비즈니스 예외의 공통 베이스. {@link ErrorCode}를 보유해
 * {@link GlobalExceptionHandler}가 HTTP 상태·응답 본문을 일괄 결정한다.
 *
 * <p>super 메시지는 로그·디버깅용 맥락(이메일, id 등)이고,
 * 클라이언트 응답에는 항상 {@link ErrorCode#message()}만 나간다.
 */
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 맥락 정보가 없는 예외용 — 메시지는 ErrorCode의 것을 그대로 쓴다. */
    protected BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
