package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jongbeom.server.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 사용자 간 데이터 격리: 다른 사용자의 데이터를 읽거나 바꿀 수 없어야 한다.
 * 남의 리소스 접근은 존재 여부를 숨기도록 403 이 아니라 404 로 응답한다.
 * (학습 관측 격리는 Clock 고정이 필요해 LearningControllerIT 에 둔다.)
 */
class DataIsolationIT extends AbstractIntegrationTest {

    private static final String NOW = "2026-06-01T14:00:00+09:00";
    private static final String TZ = "Asia/Seoul";

    private static final String SLEEP_SAMPLES = """
            [{"clientUuid":"u1","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
             {"clientUuid":"u2","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T05:00:00+09:00","hkValue":3}]""";

    private static final String UPDATE_BODY = """
            {"amount":1,"drinkName":"탈취","timestamp":"2026-06-01T10:00:00+09:00"}""";

    @Test
    void 다른사용자의_카페인기록은_수정할수없고_원본은_그대로다() throws Exception {
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");
        long aliceRecordId = createCaffeineRecord(alice, 150, "2026-06-01T09:00:00+09:00");

        mockMvc.perform(put("/api/caffeine-records/" + aliceRecordId)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + alice)
                        .param("now", NOW).param("tz", TZ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].amount").value(150))
                .andExpect(jsonPath("$.data[0].drinkName").value("커피"));
    }

    @Test
    void 다른사용자의_카페인기록은_삭제할수없고_원본은_남아있다() throws Exception {
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");
        long aliceRecordId = createCaffeineRecord(alice, 150, "2026-06-01T09:00:00+09:00");

        mockMvc.perform(delete("/api/caffeine-records/" + aliceRecordId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + alice)
                        .param("now", NOW).param("tz", TZ))
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));
    }

    @Test
    void 다른사용자의_카페인기록은_목록과_오늘연산에_섞이지_않는다() throws Exception {
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");
        createCaffeineRecord(alice, 150, "2026-06-01T09:00:00+09:00");
        createCaffeineRecord(bob, 30, "2026-06-01T10:00:00+09:00");

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + bob)
                        .param("now", NOW).param("tz", TZ))
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].amount").value(30));

        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + bob)
                        .param("now", NOW).param("tz", TZ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayTotal").value(30));
    }

    @Test
    void 다른사용자의_수면샘플은_요약에_섞이지_않고_같은_UUID도_각자_저장된다() throws Exception {
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");
        uploadSleepSamples(alice, SLEEP_SAMPLES);

        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + bob)
                        .param("date", "2026-06-01").param("tz", TZ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasData").value(false));

        // 멱등 키는 (user_id, client_uuid) — 다른 사용자의 같은 UUID 는 중복이 아니다
        JsonNode bobUpload = uploadSleepSamples(bob, SLEEP_SAMPLES);
        assertThat(bobUpload.get("inserted").asInt()).isEqualTo(2);
    }

    @Test
    void 설정변경은_다른사용자에게_영향이_없다() throws Exception {
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"halfLife":6.5,"condition":1,"bedtimeHour":1,"bedtimeMinute":30,\
                                "referenceDoseMg":120,"isLearningEnabled":false,\
                                "notifications":{"cutoff":false,"bedtimeResidual":false,"recordReminder":false}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.halfLife").value(5.0))
                .andExpect(jsonPath("$.data.condition").value(0))
                .andExpect(jsonPath("$.data.referenceDoseMg").value(75))
                .andExpect(jsonPath("$.data.isLearningEnabled").value(true))
                .andExpect(jsonPath("$.data.notifications.cutoff").value(true));
    }

    @Test
    void 로그아웃은_다른사용자의_토큰에_영향이_없다() throws Exception {
        signup("alice@b.com", DEFAULT_PASSWORD, "앨리스");
        signup("bob@b.com", DEFAULT_PASSWORD, "보브");
        String aliceAccess = login("alice@b.com", DEFAULT_PASSWORD).get("accessToken").asText();
        String bobRefresh = login("bob@b.com", DEFAULT_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"any\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + bobRefresh + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void me는_토큰주인의_정보만_돌려준다() throws Exception {
        authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("bob@b.com"));
    }
}
