package com.jongbeom.server.domain.auth.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;

/** 로그인 이메일/비밀번호 불일치. → 401 {@link ErrorCode#INVALID_CREDENTIALS}. */
public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}
