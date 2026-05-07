package com.jongbeom.server.auth.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jongbeom.server.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.auth.refresh.RefreshTokenService.IssuedRefreshToken;
import com.jongbeom.server.auth.refresh.RefreshTokenService.RotationResult;
import com.jongbeom.server.config.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-02T00:00:00Z");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);

    @Mock
    RefreshTokenRepository repository;
    @Mock
    RefreshTokenHasher hasher;

    JwtProperties jwtProperties;
    RefreshTokenService service;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties("secret", "issuer", 3600L, 1209600L);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZONE);
        service = new RefreshTokenService(repository, hasher, jwtProperties, clock);
    }

    @Test
    void issue_는_랜덤_토큰을_저장하고_평문을_반환한다() {
        given(hasher.hash(anyString())).willReturn("h".repeat(64));

        IssuedRefreshToken issued = service.issue(7L);

        assertThat(issued.value()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(1209600L);
        verify(repository).save(any(RefreshToken.class));
    }

    @Test
    void rotate_성공시_기존토큰을_revoke하고_새토큰을_저장한다() {
        String raw = "old-raw";
        String existingHash = "h".repeat(64);
        given(hasher.hash(anyString())).willReturn("new-hash");
        given(hasher.hash(raw)).willReturn(existingHash);
        RefreshToken existing = RefreshToken.issue(7L, existingHash, FIXED_NOW.plusDays(7));
        given(repository.findByTokenHash(existingHash)).willReturn(Optional.of(existing));

        RotationResult result = service.rotate(raw);

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.newToken().value()).isNotBlank();
        assertThat(result.newToken().expiresInSeconds()).isEqualTo(1209600L);
        assertThat(existing.isRevoked()).isTrue();
        verify(repository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    void rotate_존재하지않는토큰이면_InvalidRefreshTokenException() {
        given(hasher.hash("missing")).willReturn("missing-hash");
        given(repository.findByTokenHash("missing-hash")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("missing"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_만료된토큰이면_InvalidRefreshTokenException() {
        given(hasher.hash("expired")).willReturn("expired-hash");
        RefreshToken expired = RefreshToken.issue(7L, "expired-hash", FIXED_NOW.minusSeconds(1));
        given(repository.findByTokenHash("expired-hash")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("expired"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_이미revoke된토큰이면_InvalidRefreshTokenException() {
        given(hasher.hash("revoked")).willReturn("revoked-hash");
        RefreshToken revoked = RefreshToken.issue(7L, "revoked-hash", FIXED_NOW.plusDays(7));
        revoked.revoke(FIXED_NOW.minusSeconds(10));
        given(repository.findByTokenHash("revoked-hash")).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate("revoked"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void revokeAllForUser_는_레포지토리에_일괄폐기쿼리를_위임한다() {
        given(repository.revokeAllByUserId(eq(7L), any(LocalDateTime.class))).willReturn(3);

        service.revokeAllForUser(7L);

        verify(repository).revokeAllByUserId(7L, FIXED_NOW);
    }
}
