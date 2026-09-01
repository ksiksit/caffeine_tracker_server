package com.jongbeom.server.domain.user.controller;

import com.jongbeom.server.global.web.CurrentUser;
import com.jongbeom.server.domain.user.dto.MeResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 정보 조회 API. 토큰 클레임만 읽으므로 의도적으로 서비스/저장소 계층이 없다. */
@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(new MeResponse(CurrentUser.id(jwt), jwt.getClaimAsString("email")));
    }
}
