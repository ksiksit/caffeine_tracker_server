package com.jongbeom.server.domain.caffeine.controller;

import com.jongbeom.server.domain.caffeine.dto.CaffeineRecordResponse;
import com.jongbeom.server.domain.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.domain.caffeine.dto.CreateCaffeineRecordRequest;
import com.jongbeom.server.domain.caffeine.dto.UpdateCaffeineRecordRequest;
import com.jongbeom.server.domain.caffeine.service.CaffeineService;
import com.jongbeom.server.global.web.CurrentUser;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

/** 카페인 기록 CRUD + 서버 계산 현황 API. {@code /caffeine-records}(CRUD)와 {@code /caffeine/today}(계산) 두 리소스를 다룬다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CaffeineController {

    private final CaffeineService caffeineService;

    @PostMapping("/caffeine-records")
    public ResponseEntity<CaffeineRecordResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCaffeineRecordRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(caffeineService.create(userId, request));
    }

    @PutMapping("/caffeine-records/{id}")
    public ResponseEntity<CaffeineRecordResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCaffeineRecordRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(caffeineService.update(userId, id, request));
    }

    @DeleteMapping("/caffeine-records/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = CurrentUser.id(jwt);
        caffeineService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/caffeine-records")
    public ResponseEntity<List<CaffeineRecordResponse>> listToday(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @RequestParam String tz) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(caffeineService.listToday(userId, now.toInstant(), ZoneId.of(tz)));
    }

    @GetMapping("/caffeine/today")
    public ResponseEntity<CaffeineTodayResponse> today(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @RequestParam String tz) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(caffeineService.today(userId, now.toInstant(), ZoneId.of(tz)));
    }
}
