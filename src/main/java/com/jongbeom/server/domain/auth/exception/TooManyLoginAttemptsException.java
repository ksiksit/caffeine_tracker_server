package com.jongbeom.server.domain.auth.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;
import java.time.Instant;

/** 같은 이메일의 로그인 실패가 한도를 넘음. → 429 {@link ErrorCode#TOO_MANY_LOGIN_ATTEMPTS}. */
public class TooManyLoginAttemptsException extends BusinessException {
    public TooManyLoginAttemptsException(Instant blockedUntil) {
        super(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, "로그인 시도 한도 초과, 해제 시각=" + blockedUntil);
    }
}
