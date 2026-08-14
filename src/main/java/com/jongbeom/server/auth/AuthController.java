package com.jongbeom.server.auth;

import com.jongbeom.server.auth.dto.LoginRequest;
import com.jongbeom.server.auth.dto.LogoutRequest;
import com.jongbeom.server.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.auth.dto.SignupRequest;
import com.jongbeom.server.auth.dto.SignupResponse;
import com.jongbeom.server.auth.dto.TokenResponse;
import com.jongbeom.server.common.error.ErrorResponse;
import com.jongbeom.server.common.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** 인증 API. signup/login/refresh 는 공개, logout 만 인증 필요(그래서 @SecurityRequirement 는 메서드 레벨). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "회원가입 · 로그인 · 토큰 관리")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @Operation(
            summary = "회원가입",
            description = "이메일·비밀번호·닉네임으로 신규 사용자를 생성합니다. 성공 시 생성된 사용자 정보를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @ApiResponse(responseCode = "409",
                    description = "이미 사용 중인 이메일 (code: EMAIL_ALREADY_EXISTS)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(
            summary = "로그인",
            description = "이메일·비밀번호로 인증하고 Access Token / Refresh Token 한 쌍을 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(responseCode = "401",
                    description = "이메일 또는 비밀번호 불일치 (code: INVALID_CREDENTIALS)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "토큰 갱신",
            description = "Refresh Token으로 새 Access Token / Refresh Token 쌍을 발급합니다 (로테이션). 기존 Refresh Token은 무효화됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            @ApiResponse(responseCode = "401",
                    description = "유효하지 않거나 만료된 Refresh Token (code: INVALID_REFRESH_TOKEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "로그아웃",
            description = "서버에 저장된 사용자의 모든 Refresh Token을 폐기합니다. 클라이언트는 호출 후 저장된 토큰을 모두 삭제해야 합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 성공"),
            @ApiResponse(responseCode = "401",
                    description = "인증되지 않음 (Access Token 누락 또는 만료)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> logout(
            // 바디는 클라이언트 계약 호환용으로 받기만 한다 — 폐기는 토큰의 userId 기준(전 기기 로그아웃)
            @Valid @RequestBody LogoutRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        authService.logout(userId);
        return ResponseEntity.noContent().build();
    }
}
