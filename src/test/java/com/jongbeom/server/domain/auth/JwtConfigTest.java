package com.jongbeom.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jongbeom.server.global.config.JwtProperties;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtConfigTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long-xxxxxxxxxxx";

    JwtEncoder encoder;
    JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        JwtProperties properties = new JwtProperties(SECRET, "caffeine-tracker", 3600L, 1209600L);
        SecretKey key = config.jwtSecretKey(properties);
        encoder = config.jwtEncoder(key);
        decoder = config.jwtDecoder(key, properties);
    }

    @Test
    void 설정된_issuer_토큰은_통과한다() {
        String token = encode("caffeine-tracker");

        assertThat(decoder.decode(token).getSubject()).isEqualTo("7");
    }

    @Test
    void 다른_issuer_토큰은_거부한다() {
        String token = encode("other-env");

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private String encode(String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("7")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
