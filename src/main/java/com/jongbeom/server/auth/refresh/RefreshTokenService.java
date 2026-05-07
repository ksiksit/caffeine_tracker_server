package com.jongbeom.server.auth.refresh;

import com.jongbeom.server.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.config.JwtProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class RefreshTokenService {

    private static final int RAW_TOKEN_BYTES = 64;

    private final RefreshTokenRepository repository;
    private final RefreshTokenHasher hasher;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository repository,
            RefreshTokenHasher hasher,
            JwtProperties jwtProperties) {
        this(repository, hasher, jwtProperties, Clock.systemDefaultZone());
    }

    RefreshTokenService(
            RefreshTokenRepository repository,
            RefreshTokenHasher hasher,
            JwtProperties jwtProperties,
            Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(Long userId) {
        String rawToken = generateRawToken();
        String tokenHash = hasher.hash(rawToken);
        LocalDateTime expiresAt = LocalDateTime.now(clock)
                .plusSeconds(jwtProperties.refreshExpiresInSeconds());
        repository.save(RefreshToken.issue(userId, tokenHash, expiresAt));
        return new IssuedRefreshToken(rawToken, jwtProperties.refreshExpiresInSeconds());
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        String tokenHash = hasher.hash(rawToken);
        RefreshToken found = repository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    log.info("RefreshToken 회전 실패(존재하지 않음)");
                    return new InvalidRefreshTokenException();
                });
        LocalDateTime now = LocalDateTime.now(clock);
        if (!found.isUsable(now)) {
            log.info("RefreshToken 회전 실패(만료 또는 폐기) tokenId={}", found.getId());
            throw new InvalidRefreshTokenException();
        }
        found.revoke(now);
        IssuedRefreshToken issued = issue(found.getUserId());
        return new RotationResult(found.getUserId(), issued);
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        int updated = repository.revokeAllByUserId(userId, LocalDateTime.now(clock));
        log.info("사용자 RefreshToken 일괄 폐기 userId={}, count={}", userId, updated);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedRefreshToken(String value, long expiresInSeconds) {
    }

    public record RotationResult(Long userId, IssuedRefreshToken newToken) {
    }
}
