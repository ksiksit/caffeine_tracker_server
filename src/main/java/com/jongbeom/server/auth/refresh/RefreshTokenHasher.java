package com.jongbeom.server.auth.refresh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 해시(SHA-256, 소문자 hex). BCrypt 가 아닌 이유: 토큰 자체가 고엔트로피 난수라
 * 느린 해시가 불필요하고, 조회 키로 쓰려면 결정적 해시여야 한다.
 */
@Component
public class RefreshTokenHasher {

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed); // 소문자 hex — 기존 %02x 루프와 동일 출력
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
