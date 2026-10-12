package com.jongbeom.server.domain.auth.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;

/** 리프레시 토큰 서명·만료·종류 불일치, 사용자 없음, 버전 불일치(로그아웃됨). → 401 {@link ErrorCode#INVALID_REFRESH_TOKEN}. */
public class InvalidRefreshTokenException extends BusinessException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
