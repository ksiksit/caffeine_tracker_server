package com.jongbeom.server.domain.auth.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;

/** 리프레시 토큰 미존재·만료·회수됨. → 401 {@link ErrorCode#INVALID_REFRESH_TOKEN}. */
public class InvalidRefreshTokenException extends BusinessException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
