package com.jongbeom.server.caffeine;

import com.jongbeom.server.caffeine.dto.CaffeineRecordResponse;
import com.jongbeom.server.caffeine.dto.CaffeineTodayResponse;
import com.jongbeom.server.caffeine.dto.CreateCaffeineRecordRequest;
import com.jongbeom.server.caffeine.dto.UpdateCaffeineRecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Caffeine", description = "카페인 기록 · 잔류량/마감시각 계산")
@SecurityRequirement(name = "bearerAuth")
public class CaffeineController {

    private final CaffeineService service;

    @PostMapping("/caffeine-records")
    @Operation(summary = "카페인 기록 추가")
    public ResponseEntity<CaffeineRecordResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCaffeineRecordRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, request));
    }

    @PutMapping("/caffeine-records/{id}")
    @Operation(summary = "카페인 기록 수정")
    public ResponseEntity<CaffeineRecordResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCaffeineRecordRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.update(userId, id, request));
    }

    @DeleteMapping("/caffeine-records/{id}")
    @Operation(summary = "카페인 기록 삭제")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = Long.parseLong(jwt.getSubject());
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/caffeine-records")
    @Operation(summary = "오늘 카페인 기록 조회", description = "차트 시작(로컬 새벽 5시) 이후 기록만 반환합니다.")
    public ResponseEntity<List<CaffeineRecordResponse>> listToday(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "기준 시각(ISO-8601+오프셋)", example = "2026-06-01T14:30:00+09:00")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @Parameter(description = "IANA 타임존", example = "Asia/Seoul") @RequestParam String tz) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.listToday(userId, now.toInstant(), ZoneId.of(tz)));
    }

    @GetMapping("/caffeine/today")
    @Operation(summary = "오늘의 카페인 현황(서버 계산)",
            description = "settings 를 읽어 잔류량 차트·현재/취침 잔량·마감시각을 한 번에 계산해 반환합니다.")
    public ResponseEntity<CaffeineTodayResponse> today(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "기준 시각(ISO-8601+오프셋)", example = "2026-06-01T14:30:00+09:00")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime now,
            @Parameter(description = "IANA 타임존", example = "Asia/Seoul") @RequestParam String tz) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.ok(service.today(userId, now.toInstant(), ZoneId.of(tz)));
    }
}
