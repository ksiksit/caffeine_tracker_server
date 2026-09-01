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

/** Access Token(JWT) 발급. subject = users.id, 클레임 email/roles 포함. */
@Component
public class JwtTokenProvider {

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

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
