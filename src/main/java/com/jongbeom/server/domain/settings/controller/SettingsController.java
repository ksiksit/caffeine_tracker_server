package com.jongbeom.server.domain.settings.controller;

import com.jongbeom.server.global.web.CurrentUser;
import com.jongbeom.server.domain.settings.dto.SettingsResponse;
import com.jongbeom.server.domain.settings.dto.UpdateSettingsRequest;
import com.jongbeom.server.domain.settings.service.UserSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<SettingsResponse> get(@AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(SettingsResponse.from(userSettingsService.getOrCreate(userId)));
    }

    @PutMapping
    public ResponseEntity<SettingsResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateSettingsRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(SettingsResponse.from(userSettingsService.update(userId, request)));
    }
}
