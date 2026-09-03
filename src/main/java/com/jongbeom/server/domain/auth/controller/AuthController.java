package com.jongbeom.server.domain.auth.controller;

import com.jongbeom.server.domain.auth.dto.LoginRequest;
import com.jongbeom.server.domain.auth.dto.LogoutRequest;
import com.jongbeom.server.domain.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.domain.auth.dto.SignupRequest;
import com.jongbeom.server.domain.auth.dto.SignupResponse;
import com.jongbeom.server.domain.auth.dto.TokenResponse;
import com.jongbeom.server.domain.auth.service.AuthService;
import com.jongbeom.server.global.web.ApiResponse;
import com.jongbeom.server.global.web.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. signup/login/refresh 는 공개, logout 만 인증 필요. 응답은 전부 {@link ApiResponse} 봉투. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    /** 봉투 통일을 위해 204 가 아닌 200 + {@code data: null}. */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            // 바디는 클라이언트 계약 호환용으로 받기만 한다 — 폐기는 토큰의 userId 기준(전 기기 로그아웃)
            @Valid @RequestBody LogoutRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        authService.logout(userId);
        return ApiResponse.ok();
    }
}
