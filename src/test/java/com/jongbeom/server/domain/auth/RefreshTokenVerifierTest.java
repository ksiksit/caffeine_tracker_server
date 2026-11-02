package com.jongbeom.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jongbeom.server.domain.auth.RefreshTokenVerifier.RefreshClaims;
import com.jongbeom.server.domain.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.domain.user.entity.User;
import com.jongbeom.server.global.config.JwtProperties;
import com.jongbeom.server.support.UserFixture;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 리프레시 JWT 검증: 정상 클레임 추출, 그 외 실패는 전부 InvalidRefreshTokenException. */
class RefreshTokenVerifierTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long-xxxxxxxxxxx";
    private static final String OTHER_SECRET = "another-secret-key-also-at-least-32-bytes-long-yyyyyyyyyy";
    private static final String ISSUER = "test-issuer";

    private NimbusJwtEncoder encoder;
    private JwtProperties jwtProperties;
    private RefreshTokenVerifier verifier;

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static NimbusJwtEncoder encoder(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key(secret)));
    }

    @BeforeEach
    void setUp() {
        encoder = encoder(SECRET);
        jwtProperties = new JwtProperties(SECRET, ISSUER, 3600L, 1209600L);
        verifier = new RefreshTokenVerifier(key(SECRET), jwtProperties);
    }

    /** 유효한 refresh 클레임에서 일부만 바꿔 서명한 토큰. 검증기는 실제 시계를 쓰므로 iat/exp 는 현재 기준. */
    private String refreshToken(NimbusJwtEncoder signer, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(14, ChronoUnit.DAYS))
                .subject("7")
                .claim(JwtTokenProvider.TYPE_CLAIM, JwtTokenProvider.REFRESH_TYPE)
                .claim(JwtTokenProvider.VERSION_CLAIM, 3);
        customizer.accept(claims);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return signer.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void validToken_returnsUserIdAndVersion() {
        RefreshClaims claims = verifier.verify(refreshToken(encoder, c -> { }));
        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.tokenVersion()).isEqualTo(3);
    }

    @Test
    void tokenIssuedByJwtTokenProvider_isAccepted() {
        User user = UserFixture.withId(7L);
        user.invalidateTokens(); // ver = 1
        JwtTokenProvider provider = new JwtTokenProvider(encoder, jwtProperties, Clock.systemUTC());

        RefreshClaims claims = verifier.verify(provider.createRefreshToken(user).value());

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.tokenVersion()).isEqualTo(1);
    }

    @Test
    void accessToken_rejected() {
        JwtTokenProvider provider = new JwtTokenProvider(encoder, jwtProperties, Clock.systemUTC());
        assertRejected(provider.createAccessToken(UserFixture.withId(7L)).value());
    }

    @Test
    void expiredToken_rejected() {
        // 발급 15일 전 → 만료 1일 전 (기본 clock skew 60초보다 충분히 과거)
        JwtTokenProvider provider = new JwtTokenProvider(encoder, jwtProperties,
                Clock.fixed(Instant.now().minus(15, ChronoUnit.DAYS), ZoneOffset.UTC));
        assertRejected(provider.createRefreshToken(UserFixture.withId(7L)).value());
    }

    @Test
    void signedWithOtherKey_rejected() {
        assertRejected(refreshToken(encoder(OTHER_SECRET), c -> { }));
    }

    @Test
    void wrongIssuer_rejected() {
        assertRejected(refreshToken(encoder, c -> c.issuer("someone-else")));
    }

    @Test
    void wrongType_rejected() {
        assertRejected(refreshToken(encoder, c -> c.claim(JwtTokenProvider.TYPE_CLAIM, "access")));
    }

    @Test
    void missingType_rejected() {
        assertRejected(refreshToken(encoder, c -> c.claims(m -> m.remove(JwtTokenProvider.TYPE_CLAIM))));
    }

    @Test
    void missingVersion_rejected() {
        assertRejected(refreshToken(encoder, c -> c.claims(m -> m.remove(JwtTokenProvider.VERSION_CLAIM))));
    }

    @Test
    void nonNumericSubject_rejected() {
        assertRejected(refreshToken(encoder, c -> c.subject("not-a-number")));
    }

    @Test
    void malformedToken_rejected() {
        assertRejected("not-a-jwt");
    }
}
