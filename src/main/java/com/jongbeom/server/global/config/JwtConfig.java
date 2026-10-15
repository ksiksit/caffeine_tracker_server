package com.jongbeom.server.global.config;

import com.jongbeom.server.domain.auth.JwtTokenProvider;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** JWT 인코더/디코더 + 권한 변환 설정 (HS256 대칭키). */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** HS256은 RFC 7518상 256비트(32바이트) 이상 키를 요구한다. */
    private static final int MIN_KEY_BYTES = 32;

    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("app.jwt.secret 가 설정되지 않았습니다.");
        }
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret 은 최소 " + MIN_KEY_BYTES + "바이트(256비트) 이상이어야 합니다. 현재: "
                            + keyBytes.length + "바이트");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** API 인증용. refresh 토큰(typ=refresh)은 거부한다 — roles 가 없어도 authenticated() 는 통과하기 때문. */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), refreshTokenRejected()));
        return decoder;
    }

    // "typ=access 필수"가 아니라 "refresh 거부": 배포 전 발급된 access 토큰엔 typ 클레임이 없다.
    private static OAuth2TokenValidator<Jwt> refreshTokenRejected() {
        return jwt -> JwtTokenProvider.REFRESH_TYPE.equals(jwt.getClaimAsString(JwtTokenProvider.TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "refresh 토큰으로는 API 를 호출할 수 없습니다.", null))
                : OAuth2TokenValidatorResult.success();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        // prefix 빈 문자열: UserRole.authority()가 이미 "ROLE_USER" 형태로 클레임에 넣는다 (JwtTokenProvider 참조)
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
