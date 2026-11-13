package com.jongbeom.server.domain.insight.exception;

import com.jongbeom.server.global.error.BusinessException;
import com.jongbeom.server.global.error.ErrorCode;

/** 인사이트 기간 검증 실패(from > to, 31일 초과, to 가 오늘 이후). → 400 {@link ErrorCode#INVALID_PARAMETER}. */
public class InvalidInsightPeriodException extends BusinessException {
    public InvalidInsightPeriodException(String detail) {
        super(ErrorCode.INVALID_PARAMETER, "인사이트 기간이 올바르지 않습니다: " + detail);
    }
}
