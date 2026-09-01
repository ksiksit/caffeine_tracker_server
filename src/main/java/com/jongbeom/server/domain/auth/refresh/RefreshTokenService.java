package com.jongbeom.server.domain.auth.refresh;

import com.jongbeom.server.domain.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.global.config.JwtProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 리프레시 토큰 발급·회전·일괄 폐기. DB에는 SHA-256 해시만 저장한다(평문 미저장). */
@Slf4j
@Service
public class RefreshTokenService {

    /** 원문 토큰 난수 바이트 수. RefreshToken 의 tokenHash 컬럼 길이 64(SHA-256 hex)와는 별개 값이다. */
    private static final int RAW_TOKEN_BYTES = 64;

    private final RefreshTokenRepository repository;
    private final RefreshTokenHasher hasher;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    // 프로덕션 생성자. 테스트는 아래 package-private 생성자로 고정 Clock 을 주입한다.
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

    /** 회전: 기존 토큰 검증·폐기 후 새 토큰 발급. 실패는 모두 INVALID_REFRESH_TOKEN 으로 수렴. */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken found = findByHashOrThrow(hasher.hash(rawToken));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!found.isUsable(now)) {
            log.info("RefreshToken 회전 실패(만료 또는 폐기) tokenId={}", found.getId());
            throw new InvalidRefreshTokenException();
        }
        found.revoke(now);
        // 같은 클래스의 @Transactional 자기호출 — 이미 트랜잭션 안이므로 프록시 미경유여도 의미 동일
        IssuedRefreshToken issued = issue(found.getUserId());
        return new RotationResult(found.getUserId(), issued);
    }

    private RefreshToken findByHashOrThrow(String tokenHash) {
        return repository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    log.info("RefreshToken 회전 실패(존재하지 않음)");
                    return new InvalidRefreshTokenException();
                });
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
