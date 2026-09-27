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
                .andExpect(jsonPath("$.data.halfLife").value(5.0))
                .andExpect(jsonPath("$.data.condition").value(0))
                .andExpect(jsonPath("$.data.bedtimeHour").value(23))
                .andExpect(jsonPath("$.data.referenceDoseMg").value(75))
                .andExpect(jsonPath("$.data.isLearningEnabled").value(true))
                .andExpect(jsonPath("$.data.learnedMean").value(5.0))
                .andExpect(jsonPath("$.data.learnedVariance").value(2.25))
                .andExpect(jsonPath("$.data.effectiveHalfLifeHours").value(5.0))
                .andExpect(jsonPath("$.data.notifications.cutoff").value(true))
                .andExpect(jsonPath("$.data.notifications.bedtimeResidual").value(true))
                .andExpect(jsonPath("$.data.notifications.recordReminder").value(true))
                .andExpect(jsonPath("$.data.notifications.cafeNearby").value(false)); // 동 선택·위치 권한이 필요해 기본 꺼짐

        // 반감기 6.0 + 흡연(×0.5): prior 리셋 → learnedMean=6.0, effective=6.0*0.5=3.0
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":6.0,"condition":1,"bedtimeHour":1,"bedtimeMinute":30,\
                        "referenceDoseMg":75,"isLearningEnabled":true,\
                        "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learnedMean").value(6.0))
                .andExpect(jsonPath("$.data.effectiveHalfLifeHours").value(3.0));
    }

    @Test
    void settings_카페근처_토글은_생략하면_기존값_유지() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        String base = """
                {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                "referenceDoseMg":75,"isLearningEnabled":true,\
                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true%s}}""";

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(base.formatted(",\"cafeNearby\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.cafeNearby").value(true));

        // 이 필드를 모르는 이전 버전 앱의 PUT — 켜 둔 값을 끄면 안 된다
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(base.formatted("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.cafeNearby").value(true));
    }

    @Test
    void settings_알림토글_갱신_그리고_누락시_400() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":75,"isLearningEnabled":true,\
                        "notifications":{"cutoff":false,"bedtimeResidual":true,"recordReminder":false}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.cutoff").value(false))
                .andExpect(jsonPath("$.data.notifications.bedtimeResidual").value(true))
                .andExpect(jsonPath("$.data.notifications.recordReminder").value(false));

        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.cutoff").value(false))
                .andExpect(jsonPath("$.data.notifications.recordReminder").value(false));

        // notifications 는 전체 교체의 일부라 필수 — 누락 시 VALIDATION_FAILED
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":75,"isLearningEnabled":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("notifications"));
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
                .andExpect(jsonPath("$.data.amount").value(100));

        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayTotal").value(100))
                .andExpect(jsonPath("$.data.overDailyLimit").value(false))
                // 현재 잔량 ≈ 50 (1 반감기 경과)
                .andExpect(jsonPath("$.data.currentResidual",
                        Matchers.closeTo(50.0, 0.5)))
                // 취침(23:00) 시 ≈ 14.36mg (14h 경과)
                .andExpect(jsonPath("$.data.predictedAtBedtime",
                        Matchers.closeTo(14.36, 0.5)))
                .andExpect(jsonPath("$.data.cutoff.status").value("CUTOFF"))
                .andExpect(jsonPath("$.data.chart").isArray())
                .andExpect(jsonPath("$.data.chart", Matchers.not(Matchers.empty())));
    }

    @Test
    void caffeine_today_기록없으면_잔량0_차트빈배열() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                .param("now", "2026-06-01T14:00:00+09:00")
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentResidual").value(0.0))
                .andExpect(jsonPath("$.data.predictedAtBedtime").value(0.0))
                .andExpect(jsonPath("$.data.chart").isEmpty());
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
        long id = dataOf(created).get("id").asLong();

        mockMvc.perform(put("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":80,"drinkName":"콜라","timestamp":"2026-06-01T10:00:00+09:00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(80));

        mockMvc.perform(delete("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));

        mockMvc.perform(delete("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));
    }

    @Test
    void 인증없이_접근하면_401() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/caffeine/today")
                .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
