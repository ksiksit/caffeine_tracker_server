package com.jongbeom.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.jwt.*} 설정. secret 은 32바이트 이상, 만료 값들은 초 단위. */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        long expiresInSeconds,
        long refreshExpiresInSeconds
) {
}
