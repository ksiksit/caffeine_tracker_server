package com.jongbeom.server.domain.sleep.controller;

import com.jongbeom.server.global.web.CurrentUser;
import com.jongbeom.server.domain.sleep.dto.SleepSummaryResponse;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesRequest;
import com.jongbeom.server.domain.sleep.dto.UploadSleepSamplesResponse;
import com.jongbeom.server.domain.sleep.service.SleepService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 수면 API — 원시 샘플 업로드(멱등)와 서버 병합·요약 조회. */
@RestController
@RequestMapping("/api/sleep")
@RequiredArgsConstructor
public class SleepController {

    private final SleepService sleepService;

    @PostMapping("/samples")
    public ResponseEntity<UploadSleepSamplesResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UploadSleepSamplesRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(sleepService.upload(userId, request));
    }

    @GetMapping("/summary")
    public ResponseEntity<SleepSummaryResponse> summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String tz) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(sleepService.summary(userId, date, ZoneId.of(tz)));
    }
}
