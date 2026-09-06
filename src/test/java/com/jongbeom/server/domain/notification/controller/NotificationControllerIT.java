package com.jongbeom.server.domain.notification.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 알림 계획 API 통합 테스트. 기본 설정(취침 23:00, 기준 75mg, 반감기 5h)에서 기록이 없으면
 * 마감 = 23:00 − hoursToDecay(75→50, 5h)=2h55m29.325s = 20:04:30.675 KST(11:04:30.675Z),
 * 섭취 마감 알림은 30분 전 19:34:30.675 KST = 10:34:30.675Z.
 */
class NotificationControllerIT extends AbstractIntegrationTest {

    private static final String NOW_14_KST = "2026-06-01T14:00:00+09:00";

    private ResultActions plan(String accessToken, String now) throws Exception {
        return mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", now)
                .param("tz", "Asia/Seoul"));
    }

    /** 설정 전체 교체 PUT — referenceDoseMg 와 cutoff 토글만 바꾸고 나머지는 기본값. */
    private void putSettings(String accessToken, int referenceDoseMg, boolean notifyCutoff) throws Exception {
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":%d,"isLearningEnabled":true,\
                        "notifications":{"cutoff":%b,"bedtimeResidual":true,"recordReminder":true}}"""
                        .formatted(referenceDoseMg, notifyCutoff)))
                .andExpect(status().isOk());
    }

    @Test
    void 기본설정_기록없음_마감30분전_CUTOFF_항목1개() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.now").value("2026-06-01T05:00:00Z"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T10:34:30.675Z"))
                .andExpect(jsonPath("$.data.items[0].title").value("섭취 마감 30분 전"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("23:00")))
                .andExpect(jsonPath("$.data.items[0].body", containsString("75mg")))
                .andExpect(jsonPath("$.data.items[0].body", containsString("20:04")));
    }

    @Test
    void 토글_off면_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 75, false);

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 취침잔량_이미초과면_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        // 400mg @ 13:00 KST → 23:00 취침까지 2 반감기 = 100mg > 50mg → ALREADY_EXCEEDED(마감 없음)
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":400,"drinkName":"에스프레소","timestamp":"2026-06-01T13:00:00+09:00"}"""))
                .andExpect(status().isCreated());

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 마감까지_30분미만_남으면_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        // now 20:00 KST: 마감 20:04:30 은 아직이지만 30분 전(19:34:30)은 이미 지남
        plan(accessToken, "2026-06-01T20:00:00+09:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 기준용량이_헤드룸_이하면_SAFE_ANYTIME_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 40, true); // 40mg ≤ 취침 헤드룸 50mg → 언제든 가능, 마감시각 없음

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 잘못된_tz는_400_INVALID_PARAMETER() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", NOW_14_KST)
                .param("tz", "Not/AZone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(get("/api/notifications/plan")
                .param("now", NOW_14_KST).param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
