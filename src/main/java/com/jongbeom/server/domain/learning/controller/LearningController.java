package com.jongbeom.server.domain.learning.controller;

import com.jongbeom.server.global.web.CurrentUser;
import com.jongbeom.server.domain.learning.dto.LearningDashboardResponse;
import com.jongbeom.server.domain.learning.dto.LearningRunResponse;
import com.jongbeom.server.domain.learning.dto.ObservationResponse;
import com.jongbeom.server.domain.learning.service.LearningService;
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

/** 베이지안 반감기 학습 API — 배치 실행(run)과 관측 이력·대시보드 조회. */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;

    @PostMapping("/run")
    public ResponseEntity<LearningRunResponse> run(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String tz) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(learningService.run(userId, ZoneId.of(tz)));
    }

    // observations/dashboard 는 저장된 날짜를 그대로 반환하므로 tz 파라미터가 필요 없다 (run 만 달력 연산).

    @GetMapping("/observations")
    public ResponseEntity<List<ObservationResponse>> observations(@AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(learningService.observations(userId));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<LearningDashboardResponse> dashboard(@AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(learningService.dashboard(userId));
    }
}
