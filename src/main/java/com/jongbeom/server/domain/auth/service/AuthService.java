package com.jongbeom.server.domain.auth.service;

import com.jongbeom.server.domain.auth.JwtTokenProvider;
import com.jongbeom.server.domain.auth.LoginAttemptLimiter;
import com.jongbeom.server.domain.auth.LoginMetrics;
import com.jongbeom.server.domain.auth.RefreshTokenVerifier;
import com.jongbeom.server.domain.auth.RefreshTokenVerifier.RefreshClaims;
import com.jongbeom.server.domain.auth.dto.LoginRequest;
import com.jongbeom.server.domain.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.domain.auth.dto.SignupRequest;
import com.jongbeom.server.domain.auth.dto.SignupResponse;
import com.jongbeom.server.domain.auth.dto.TokenResponse;
import com.jongbeom.server.domain.auth.exception.DuplicateEmailException;
import com.jongbeom.server.domain.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.domain.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import com.jongbeom.server.domain.user.entity.User;
import com.jongbeom.server.domain.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입·로그인·토큰 갱신·로그아웃. 토큰은 access/refresh 모두 JWT(JwtTokenProvider)이고 DB에 저장하지 않는다.
 * 무효화는 users.token_version 하나로 한다: 로그아웃 시 +1 → ver 클레임이 다른 refresh 토큰은 거부.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenVerifier refreshTokenVerifier;
    private final UserSettingsService userSettingsService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final LoginMetrics loginMetrics;

    /** 없는 이메일 로그인에도 bcrypt 비교를 한 번 하기 위한 해시. 실제 인코더로 만들어 비용(strength)이 같다. */
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyPasswordHash() {
        dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.nickname());
        User saved;
        try {
            saved = userRepository.save(user); // IDENTITY 라 INSERT 가 여기서 바로 실행된다
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일 동시 가입: 위 확인을 둘 다 통과한 뒤 먼저 커밋한 쪽이 있으면 uk_users_email 위반으로 여기 온다
            throw new DuplicateEmailException(request.email());
        }
        userSettingsService.createDefault(saved.getId());
        log.info("회원가입 성공 userId={}", saved.getId());
        return new SignupResponse(saved.getId(), saved.getEmail(), saved.getNickname());
    }

    /**
     * 시도 제한(이메일 단위)을 먼저 통과해야 한다 — {@link LoginAttemptLimiter}.
     * 없는 이메일도 bcrypt 비교를 똑같이 한 번 해서, 응답 시간으로 가입 여부가 드러나지 않게 한다.
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        loginAttemptLimiter.acquire(request.email());
        User user = userRepository.findByEmail(request.email()).orElse(null);
        String hashToCheck = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(request.password(), hashToCheck);
        if (user == null) {
            loginMetrics.record(LoginMetrics.Outcome.UNKNOWN_EMAIL);
            log.info("로그인 실패(존재하지 않는 이메일)");
            throw new InvalidCredentialsException();
        }
        if (!matches) {
            loginMetrics.record(LoginMetrics.Outcome.WRONG_PASSWORD);
            log.info("로그인 실패(비밀번호 불일치) userId={}", user.getId());
            throw new InvalidCredentialsException();
        }
        loginAttemptLimiter.reset(request.email());
        loginMetrics.record(LoginMetrics.Outcome.SUCCESS);
        log.info("로그인 성공 userId={}", user.getId());
        return issueTokens(user);
    }

    /** 회전 없음: 재발급 후에도 기존 refresh 토큰은 만료·로그아웃 전까지 유효하다. */
    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshClaims claims = refreshTokenVerifier.verify(request.refreshToken());
        User user = userRepository.findById(claims.userId())
                .orElseThrow(InvalidRefreshTokenException::new);
        if (user.getTokenVersion() != claims.tokenVersion()) {
            log.info("토큰 재발급 실패(버전 불일치 — 로그아웃됨) userId={}", user.getId());
            throw new InvalidRefreshTokenException();
        }
        log.info("토큰 재발급 성공 userId={}", user.getId());
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        JwtTokenProvider.IssuedToken accessToken = jwtTokenProvider.createAccessToken(user);
        JwtTokenProvider.IssuedToken refreshToken = jwtTokenProvider.createRefreshToken(user);
        return TokenResponse.bearer(
                accessToken.value(), refreshToken.value(),
                accessToken.expiresInSeconds(), refreshToken.expiresInSeconds());
    }

    @Transactional
    public void logout(Long userId) {
        userRepository.findById(userId).ifPresent(User::invalidateTokens);
        log.info("로그아웃 처리 userId={}", userId);
    }
}
