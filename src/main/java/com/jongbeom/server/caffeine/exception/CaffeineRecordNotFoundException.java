package com.jongbeom.server.caffeine.exception;

public class CaffeineRecordNotFoundException extends RuntimeException {
    public CaffeineRecordNotFoundException(Long id) {
        super("카페인 기록을 찾을 수 없습니다: " + id);
    }
}
