package com.jongbeom.server.domain.auth.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;

/** 회원가입 시 이미 존재하는 이메일. → 409 {@link ErrorCode#EMAIL_ALREADY_EXISTS}. */
public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super(ErrorCode.EMAIL_ALREADY_EXISTS, "이미 사용 중인 이메일입니다: " + email);
    }
}
