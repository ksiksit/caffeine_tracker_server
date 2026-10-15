package com.jongbeom.server.domain.auth.service;

import com.jongbeom.server.domain.auth.JwtTokenProvider;
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
import com.jongbeom.server.domain.user.entity.User;
import com.jongbeom.server.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.nickname());
        User saved = userRepository.save(user);
        log.info("회원가입 성공 userId={}, email={}", saved.getId(), saved.getEmail());
        return new SignupResponse(saved.getId(), saved.getEmail(), saved.getNickname());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = findByEmailOrThrow(request.email());
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.info("로그인 실패(비밀번호 불일치) userId={}", user.getId());
            throw new InvalidCredentialsException();
        }
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

    private User findByEmailOrThrow(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.info("로그인 실패(존재하지 않는 이메일) email={}", email);
                    return new InvalidCredentialsException();
                });
    }

    @Transactional
    public void logout(Long userId) {
        userRepository.findById(userId).ifPresent(User::invalidateTokens);
        log.info("로그아웃 처리 userId={}", userId);
    }
}
