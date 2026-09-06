package com.jongbeom.server.domain.notification.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** 알림 계획 API 통합 테스트. 종류별 생성은 아직 없으므로 뼈대(파라미터·봉투·빈 목록)만 검증한다. */
class NotificationControllerIT extends AbstractIntegrationTest {

    @Test
    void 알림계획_조회_빈목록_now는_UTC로_echo() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.now").value("2026-06-01T05:00:00Z"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 잘못된_tz는_400_INVALID_PARAMETER() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Not/AZone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(get("/api/notifications/plan")
                .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
