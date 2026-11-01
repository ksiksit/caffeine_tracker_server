package com.jongbeom.server.domain.auth;

import com.jongbeom.server.domain.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.global.config.JwtProperties;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 리프레시 JWT 검증(서명·만료·issuer·typ=refresh). 버전 대조는 users 조회가 필요해 AuthService 가 한다.
 * API 인증용 JwtDecoder 빈과 분리한 이유: 그 빈은 반대로 typ=refresh 를 거부해야 한다(JwtConfig 참조).
 */
@Slf4j
@Component
public class RefreshTokenVerifier {

    private final NimbusJwtDecoder decoder;

    public RefreshTokenVerifier(SecretKey jwtSecretKey, JwtProperties jwtProperties) {
        this.decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()),
                refreshTypeRequired()));
    }

    /** 실패는 모두 INVALID_REFRESH_TOKEN 으로 수렴. */
    public RefreshClaims verify(String rawToken) {
        try {
            Jwt jwt = decoder.decode(rawToken);
            Number version = jwt.getClaim(JwtTokenProvider.VERSION_CLAIM);
            if (version == null) {
                throw new InvalidRefreshTokenException();
            }
            return new RefreshClaims(Long.parseLong(jwt.getSubject()), version.intValue());
        } catch (JwtException | NumberFormatException e) {
            log.info("RefreshToken 검증 실패: {}", e.getMessage());
            throw new InvalidRefreshTokenException();
        }
    }

    private static OAuth2TokenValidator<Jwt> refreshTypeRequired() {
        return jwt -> JwtTokenProvider.REFRESH_TYPE.equals(jwt.getClaimAsString(JwtTokenProvider.TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "refresh 토큰이 아닙니다.", null));
    }

    public record RefreshClaims(Long userId, int tokenVersion) {
    }
}
