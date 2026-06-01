package com.jongbeom.server.learning;

import com.jongbeom.server.learning.dto.LearningDashboardResponse;
import com.jongbeom.server.learning.dto.LearningRunResponse;
import com.jongbeom.server.learning.dto.ObservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
@Tag(name = "Learning", description = "베이지안 반감기 학습 · 대시보드")
@SecurityRequirement(name = "bearerAuth")
public class LearningController {

    private final LearningService service;

    @PostMapping("/run")
    @Operation(summary = "학습 실행", description = "최근 7일 중 미학습 night를 오래된 순으로 배치 학습합니다(순차 prior).")
    public ResponseEntity<LearningRunResponse> run(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "IANA 타임존", example = "Asia/Seoul") @RequestParam String tz) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.run(userId, ZoneId.of(tz)));
    }

    @GetMapping("/observations")
    @Operation(summary = "학습 관측 이력", description = "날짜 오름차순.")
    public ResponseEntity<List<ObservationResponse>> observations(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.observations(userId));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "학습 대시보드(서버 계산)", description = "관측 + 신뢰구간·신뢰도·R²·RMSE·히스토그램.")
    public ResponseEntity<LearningDashboardResponse> dashboard(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.dashboard(userId));
    }
}
