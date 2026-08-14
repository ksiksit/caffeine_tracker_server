package com.jongbeom.server.sleep;

import com.jongbeom.server.common.web.CurrentUser;
import com.jongbeom.server.common.web.TimeParamDocs;
import com.jongbeom.server.sleep.dto.SleepSummaryResponse;
import com.jongbeom.server.sleep.dto.UploadSleepSamplesRequest;
import com.jongbeom.server.sleep.dto.UploadSleepSamplesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Sleep", description = "수면 원시 샘플 업로드 · 병합/요약")
@SecurityRequirement(name = "bearerAuth")
public class SleepController {

    private final SleepService sleepService;

    @PostMapping("/samples")
    @Operation(summary = "수면 원시 샘플 업로드", description = "client_uuid 멱등. 기기에서 읽은 HealthKit 샘플을 배치 업로드합니다.")
    public ResponseEntity<UploadSleepSamplesResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UploadSleepSamplesRequest request) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(sleepService.upload(userId, request));
    }

    @GetMapping("/summary")
    @Operation(summary = "수면 요약(서버 병합·계산)",
            description = "date(아침 기준) 전날 18:00~당일 12:00 윈도우 샘플을 병합해 총수면·효율·SOL·단계별 시간을 반환합니다.")
    public ResponseEntity<SleepSummaryResponse> summary(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "대상 날짜(아침 기준)", example = "2026-06-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = TimeParamDocs.TZ_DESCRIPTION, example = TimeParamDocs.TZ_EXAMPLE)
            @RequestParam String tz) {
        Long userId = CurrentUser.id(jwt);
        return ResponseEntity.ok(sleepService.summary(userId, date, ZoneId.of(tz)));
    }
}
