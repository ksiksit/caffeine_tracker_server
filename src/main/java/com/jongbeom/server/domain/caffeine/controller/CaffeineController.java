package com.jongbeom.server.domain.caffeine.controller;

import com.jongbeom.server.domain.caffeine.dto.CaffeineRecordResponse;
import com.jongbeom.server.domain.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.domain.caffeine.dto.CreateCaffeineRecordRequest;
import com.jongbeom.server.domain.caffeine.dto.UpdateCaffeineRecordRequest;
import com.jongbeom.server.domain.caffeine.service.CaffeineService;
import com.jongbeom.server.global.web.ApiResponse;
import com.jongbeom.server.global.web.CurrentUser;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카페인 기록 CRUD + 서버 계산 현황 API. {@code /caffeine-records}(CRUD)와 {@code /caffeine/today}(계산) 두 리소스를 다룬다.
 * {@code tz}는 String→{@link ZoneId} 자동 변환 — 잘못된 값이면 400 INVALID_PARAMETER(GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CaffeineController {

    private final CaffeineService caffeineService;

    @PostMapping("/caffeine-records")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CaffeineRecordResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCaffeineRecordRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(caffeineService.create(userId, request));
    }

    @PutMapping("/caffeine-records/{id}")
    public ApiResponse<CaffeineRecordResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCaffeineRecordRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(caffeineService.update(userId, id, request));
    }

    /** 봉투 통일을 위해 204 가 아닌 200 + {@code data: null}. */
    @DeleteMapping("/caffeine-records/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = CurrentUser.id(jwt);
        caffeineService.delete(userId, id);
        return ApiResponse.ok();
    }

    @GetMapping("/caffeine-records")
    public ApiResponse<List<CaffeineRecordResponse>> listToday(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @RequestParam ZoneId tz) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(caffeineService.listToday(userId, now.toInstant(), tz));
    }

    @GetMapping("/caffeine/today")
    public ApiResponse<CaffeineTodayResponse> today(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @RequestParam ZoneId tz) {
        Long userId = CurrentUser.id(jwt);
        return ApiResponse.ok(caffeineService.today(userId, now.toInstant(), tz));
    }
}
