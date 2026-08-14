package com.jongbeom.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jongbeom.server.config.JwtProperties;
import com.jongbeom.server.support.UserFixture;
import com.jongbeom.server.user.entity.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long-xxxxxxxxxxx";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-02T00:00:00Z");

    JwtTokenProvider provider;
    JwtDecoder decoder;
    JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        decoder = nimbusDecoder;
        jwtProperties = new JwtProperties(SECRET, "test-issuer", 3600L, 1209600L);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        provider = new JwtTokenProvider(encoder, jwtProperties, clock);
    }

    @Test
    void createAccessToken_은_iss_iat_exp_sub_email_roles_클레임을_정확히_담는다() {
        User user = UserFixture.withId(7L);

        JwtTokenProvider.IssuedToken issued = provider.createAccessToken(user);
        Jwt jwt = decoder.decode(issued.value());

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("test-issuer");
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("a@b.com");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getIssuedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(jwt.getExpiresAt()).isEqualTo(FIXED_INSTANT.plusSeconds(3600));
    }

    @Test
    void createAccessToken_의_만료시각은_iat_더하기_expiresInSeconds다() {
        User user = UserFixture.withId(7L);

        JwtTokenProvider.IssuedToken issued = provider.createAccessToken(user);
        Jwt jwt = decoder.decode(issued.value());

        assertThat(issued.expiresInSeconds()).isEqualTo(3600L);
        assertThat(jwt.getExpiresAt()).isEqualTo(FIXED_INSTANT.plusSeconds(jwtProperties.expiresInSeconds()));
    }

    @Test
    void createAccessToken_은_HS256_알고리즘으로_서명한다() {
        User user = UserFixture.withId(7L);

        JwtTokenProvider.IssuedToken issued = provider.createAccessToken(user);
        Jwt jwt = decoder.decode(issued.value());

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
    }
}
