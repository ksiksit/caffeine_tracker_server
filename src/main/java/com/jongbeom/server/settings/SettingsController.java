package com.jongbeom.server.settings;

import com.jongbeom.server.settings.dto.SettingsResponse;
import com.jongbeom.server.settings.dto.UpdateSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "유저 설정 · 학습 상태")
@SecurityRequirement(name = "bearerAuth")
public class SettingsController {

    private final UserSettingsService service;

    @GetMapping
    @Operation(summary = "내 설정 조회", description = "없으면 기본값으로 생성해 반환합니다.")
    public ResponseEntity<SettingsResponse> get(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(SettingsResponse.from(service.getOrCreate(userId)));
    }

    @PutMapping
    @Operation(summary = "내 설정 갱신", description = "전체 교체. 반감기 변경 시 학습 prior 가 리셋됩니다.")
    public ResponseEntity<SettingsResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateSettingsRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(SettingsResponse.from(service.update(userId, request)));
    }
}
