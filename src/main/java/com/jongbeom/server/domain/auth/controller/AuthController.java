package com.jongbeom.server.domain.auth.controller;

import com.jongbeom.server.domain.auth.dto.LoginRequest;
import com.jongbeom.server.domain.auth.dto.LogoutRequest;
import com.jongbeom.server.domain.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.domain.auth.dto.SignupRequest;
import com.jongbeom.server.domain.auth.dto.SignupResponse;
import com.jongbeom.server.domain.auth.dto.TokenResponse;
import com.jongbeom.server.domain.auth.service.AuthService;
import com.jongbeom.server.global.web.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. signup/login/refresh 는 공개, logout 만 인증 필요. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            // 바디는 클라이언트 계약 호환용으로 받기만 한다 — 폐기는 토큰의 userId 기준(전 기기 로그아웃)
            @Valid @RequestBody LogoutRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        authService.logout(userId);
        return ResponseEntity.noContent().build();
    }
}
