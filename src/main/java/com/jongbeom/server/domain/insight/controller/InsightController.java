package com.jongbeom.server.domain.insight.controller;

import com.jongbeom.server.domain.insight.dto.CaffeineSleepReportResponse;
import com.jongbeom.server.domain.insight.dto.IntakeStatsResponse;
import com.jongbeom.server.domain.insight.service.InsightService;
import com.jongbeom.server.global.web.ApiResponse;
import com.jongbeom.server.global.web.CurrentUser;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인사이트 API — 기간별 섭취 통계와 카페인·수면 상관 리포트. 기간 검증(from ≤ to, 31일 이하, to ≤ 오늘)은 서비스가 한다. */
@RestController
@RequestMapping("/api/insight")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;

    @GetMapping("/intake-stats")
    public ApiResponse<IntakeStatsResponse> intakeStats(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam ZoneId tz) {
        return ApiResponse.ok(insightService.intakeStats(CurrentUser.id(jwt), from, to, tz));
    }

    @GetMapping("/caffeine-sleep")
    public ApiResponse<CaffeineSleepReportResponse> caffeineSleep(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam ZoneId tz) {
        return ApiResponse.ok(insightService.caffeineSleepReport(CurrentUser.id(jwt), from, to, tz));
    }
}
