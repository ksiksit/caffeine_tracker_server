package com.jongbeom.server.domain.notification.controller;

import com.jongbeom.server.domain.notification.dto.NotificationPlanResponse;
import com.jongbeom.server.domain.notification.service.NotificationPlanService;
import com.jongbeom.server.global.web.ApiResponse;
import com.jongbeom.server.global.web.CurrentUser;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 알림 계획 API. 파라미터 바인딩은 {@code CaffeineController.today} 와 동일(잘못된 tz/now 는 400 INVALID_PARAMETER). */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationPlanService notificationPlanService;

    @GetMapping("/plan")
    public ApiResponse<NotificationPlanResponse> plan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @RequestParam ZoneId tz) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(notificationPlanService.plan(userId, now.toInstant(), tz));
    }
}
