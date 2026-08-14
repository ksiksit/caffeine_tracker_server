package com.jongbeom.server.user;

import com.jongbeom.server.common.error.ErrorResponse;
import com.jongbeom.server.common.web.CurrentUser;
import com.jongbeom.server.user.dto.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 정보 조회 API. 토큰 클레임만 읽으므로 의도적으로 서비스/저장소 계층이 없다. */
@RestController
@RequestMapping("/api")
@Tag(name = "User", description = "사용자 정보")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    @GetMapping("/me")
    @Operation(
            summary = "현재 사용자 정보 조회",
            description = "Access Token에 담긴 사용자 식별자로 본인 정보를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401",
                    description = "인증되지 않음 (Access Token 누락 또는 만료)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(new MeResponse(CurrentUser.id(jwt), jwt.getClaimAsString("email")));
    }
}
