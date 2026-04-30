package com.jongbeom.server.auth;

import com.jongbeom.server.auth.dto.LoginRequest;
import com.jongbeom.server.auth.dto.SignupRequest;
import com.jongbeom.server.auth.dto.SignupResponse;
import com.jongbeom.server.auth.dto.TokenResponse;
import com.jongbeom.server.auth.exception.DuplicateEmailException;
import com.jongbeom.server.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.user.User;
import com.jongbeom.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

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

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    log.info("로그인 실패(존재하지 않는 이메일) email={}", request.email());
                    return new InvalidCredentialsException();
                });
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.info("로그인 실패(비밀번호 불일치) userId={}", user.getId());
            throw new InvalidCredentialsException();
        }
        JwtTokenProvider.IssuedToken issued = jwtTokenProvider.createAccessToken(user);
        log.info("로그인 성공 userId={}", user.getId());
        return TokenResponse.bearer(issued.value(), issued.expiresInSeconds());
    }
}
