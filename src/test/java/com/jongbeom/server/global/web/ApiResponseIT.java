package com.jongbeom.server.global.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** 공통 응답 봉투 통합 테스트 — 성공 형태, 시큐리티 401, 프레임워크 400/404/405 가 전부 같은 봉투인지. */
class ApiResponseIT extends AbstractIntegrationTest {

    @Test
    void 성공응답은_success_true_data_그리고_error_message_null() throws Exception {
        String accessToken = authToken("env@b.com", "봉투");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("env@b.com"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()));
    }

    @Test
    void 토큰없으면_401_UNAUTHORIZED_봉투() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    void 토큰이_무효하면_401_UNAUTHORIZED_봉투() throws Exception {
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 잘못된_tz는_400_INVALID_PARAMETER() throws Exception {
        String accessToken = authToken("env@b.com", "봉투");
        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                        .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Not/AZone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("요청 파라미터가 올바르지 않습니다."));
    }

    @Test
    void 필수_파라미터_누락은_400_INVALID_PARAMETER() throws Exception {
        String accessToken = authToken("env@b.com", "봉투");
        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void 없는_경로는_404_NOT_FOUND_봉투() throws Exception {
        String accessToken = authToken("env@b.com", "봉투");
        mockMvc.perform(get("/api/nope").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 허용되지_않은_메서드는_405_METHOD_NOT_ALLOWED_봉투() throws Exception {
        String accessToken = authToken("env@b.com", "봉투");
        mockMvc.perform(delete("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
}
