package com.jongbeom.server.domain.auth;

import com.jongbeom.server.global.config.JwtProperties;
import com.jongbeom.server.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Access/Refresh Token(JWT) 발급. subject = users.id.
 * access 는 email/roles 클레임, refresh 는 typ=refresh + ver(users.token_version) 클레임을 담는다.
 */
@Component
public class JwtTokenProvider {

    /** 토큰 종류 클레임. refresh 토큰만 값을 넣는다(access 는 없음 — 배포 전 발급분과 호환). */
    public static final String TYPE_CLAIM = "typ";
    public static final String REFRESH_TYPE = "refresh";
    /** refresh 토큰의 users.token_version 스냅샷 클레임. */
    public static final String VERSION_CLAIM = "ver";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    // 프로덕션 생성자. 테스트는 아래 package-private 생성자로 고정 Clock 을 주입한다 (ClockConfig 빈 미사용 — 사유는 ClockConfig 참조).
    @Autowired
    public JwtTokenProvider(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this(jwtEncoder, jwtProperties, Clock.systemUTC());
    }

    JwtTokenProvider(JwtEncoder jwtEncoder, JwtProperties jwtProperties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    public IssuedToken createAccessToken(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.expiresInSeconds());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().authority()))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, jwtProperties.expiresInSeconds());
    }

    public IssuedToken createRefreshToken(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.refreshExpiresInSeconds());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim(TYPE_CLAIM, REFRESH_TYPE)
                .claim(VERSION_CLAIM, user.getTokenVersion())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, jwtProperties.refreshExpiresInSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
