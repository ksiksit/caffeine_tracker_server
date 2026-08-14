package com.jongbeom.server.auth;

import com.jongbeom.server.auth.dto.LoginRequest;
import com.jongbeom.server.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.auth.dto.SignupRequest;
import com.jongbeom.server.auth.dto.SignupResponse;
import com.jongbeom.server.auth.dto.TokenResponse;
import com.jongbeom.server.auth.exception.DuplicateEmailException;
import com.jongbeom.server.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.auth.refresh.RefreshTokenService;
import com.jongbeom.server.auth.refresh.RefreshTokenService.IssuedRefreshToken;
import com.jongbeom.server.auth.refresh.RefreshTokenService.RotationResult;
import com.jongbeom.server.user.User;
import com.jongbeom.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입·로그인·토큰 갱신·로그아웃. 토큰 발급은 JwtTokenProvider/RefreshTokenService에 위임. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

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
        JwtTokenProvider.IssuedToken accessToken = jwtTokenProvider.createAccessToken(user);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId());
        log.info("로그인 성공 userId={}", user.getId());
        return TokenResponse.bearer(
                accessToken.value(), refreshToken.value(),
                accessToken.expiresInSeconds(), refreshToken.expiresInSeconds());
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        RotationResult result = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(result.userId())
                .orElseThrow(InvalidRefreshTokenException::new);
        JwtTokenProvider.IssuedToken accessToken = jwtTokenProvider.createAccessToken(user);
        log.info("토큰 회전 성공 userId={}", user.getId());
        return TokenResponse.bearer(
                accessToken.value(), result.newToken().value(),
                accessToken.expiresInSeconds(), result.newToken().expiresInSeconds());
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
        refreshTokenService.revokeAllForUser(userId);
        log.info("로그아웃 처리 userId={}", userId);
    }
}
