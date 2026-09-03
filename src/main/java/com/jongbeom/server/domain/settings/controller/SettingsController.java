package com.jongbeom.server.domain.settings.controller;

import com.jongbeom.server.domain.settings.dto.SettingsResponse;
import com.jongbeom.server.domain.settings.dto.UpdateSettingsRequest;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import com.jongbeom.server.global.web.ApiResponse;
import com.jongbeom.server.global.web.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 유저 설정 API — 조회(없으면 생성)와 전체 교체 갱신. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UserSettingsService userSettingsService;

    @GetMapping
    public ApiResponse<SettingsResponse> get(@AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(SettingsResponse.from(userSettingsService.getOrCreate(userId)));
    }

    @PutMapping
    public ApiResponse<SettingsResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateSettingsRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(SettingsResponse.from(userSettingsService.update(userId, request)));
    }
}
