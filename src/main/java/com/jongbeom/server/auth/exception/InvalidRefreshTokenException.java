package com.jongbeom.server.auth.exception;

import com.jongbeom.server.common.error.BusinessException;
import com.jongbeom.server.common.error.ErrorCode;

/** 리프레시 토큰 미존재·만료·회수됨. → 401 {@link ErrorCode#INVALID_REFRESH_TOKEN}. */
public class InvalidRefreshTokenException extends BusinessException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
