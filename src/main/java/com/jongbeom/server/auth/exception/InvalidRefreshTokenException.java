package com.jongbeom.server.auth.exception;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("RefreshToken 이 유효하지 않습니다.");
    }
}
