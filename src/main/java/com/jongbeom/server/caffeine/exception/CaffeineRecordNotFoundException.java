package com.jongbeom.server.caffeine.exception;

import com.jongbeom.server.common.error.BusinessException;
import com.jongbeom.server.common.error.ErrorCode;

/** 본인 소유가 아니거나 존재하지 않는 카페인 기록. → 404 {@link ErrorCode#CAFFEINE_RECORD_NOT_FOUND}. */
public class CaffeineRecordNotFoundException extends BusinessException {
    public CaffeineRecordNotFoundException(Long id) {
        super(ErrorCode.CAFFEINE_RECORD_NOT_FOUND, "카페인 기록을 찾을 수 없습니다: " + id);
    }
}
