package com.jongbeom.server.domain.caffeine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** settings + caffeine 도메인 통합 테스트 (인증→CRUD→타임존 기반 today 연산). today가 두 도메인을 함께 쓰므로 한 클래스로 유지. */
class CaffeineAndSettingsIT extends AbstractIntegrationTest {

    @Test
    void settings_기본값_조회_그리고_갱신시_prior리셋() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");

        // 기본값 자동 생성 (5.0=기본 반감기, 2.25=모집단 prior 분산 1.5², 23시/75mg=기본 취침·기준용량)
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.halfLife").value(5.0))
                .andExpect(jsonPath("$.condition").value(0))
                .andExpect(jsonPath("$.bedtimeHour").value(23))
                .andExpect(jsonPath("$.referenceDoseMg").value(75))
                .andExpect(jsonPath("$.learnedMean").value(5.0))
                .andExpect(jsonPath("$.learnedVariance").value(2.25))
                .andExpect(jsonPath("$.effectiveHalfLifeHours").value(5.0));

        // 반감기 6.0 + 흡연(×0.5): prior 리셋 → learnedMean=6.0, effective=6.0*0.5=3.0
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":6.0,"condition":1,"bedtimeHour":1,"bedtimeMinute":30,\
                        "referenceDoseMg":75,"isLearningEnabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learnedMean").value(6.0))
                .andExpect(jsonPath("$.effectiveHalfLifeHours").value(3.0));
    }

    @Test
    void caffeine_today_타임존기준_잔량계산() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");

        // 100mg @ 2026-06-01 09:00 KST. now=14:00 KST → 5시간 경과, 반감기 5h → 잔량 50mg
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":100,"drinkName":"아메리카노","timestamp":"2026-06-01T09:00:00+09:00"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(100));

        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayTotal").value(100))
                .andExpect(jsonPath("$.overDailyLimit").value(false))
                // 현재 잔량 ≈ 50 (1 반감기 경과)
                .andExpect(jsonPath("$.currentResidual",
                        Matchers.closeTo(50.0, 0.5)))
                // 취침(23:00) 시 ≈ 14.36mg (14h 경과)
                .andExpect(jsonPath("$.predictedAtBedtime",
                        Matchers.closeTo(14.36, 0.5)))
                .andExpect(jsonPath("$.cutoff.status").value("CUTOFF"))
                .andExpect(jsonPath("$.chart").isArray())
                .andExpect(jsonPath("$.chart", Matchers.not(Matchers.empty())));
    }

    @Test
    void caffeine_today_기록없으면_잔량0_차트빈배열() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentResidual").value(0.0))
                .andExpect(jsonPath("$.predictedAtBedtime").value(0.0))
                .andExpect(jsonPath("$.chart").isEmpty());
    }

    @Test
    void caffeine_수정_삭제_그리고_없는기록_404() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        MvcResult created = mockMvc.perform(post("/api/caffeine-records")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":150,"drinkName":"라떼","timestamp":"2026-06-01T09:00:00+09:00"}"""))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":80,"drinkName":"콜라","timestamp":"2026-06-01T10:00:00+09:00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(80));

        mockMvc.perform(delete("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAFFEINE_RECORD_NOT_FOUND"));
    }

    @Test
    void 인증없이_접근하면_401() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/caffeine/today")
                .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
