package com.jongbeom.server.global.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/** 사용자 간 데이터 격리: 다른 사용자의 기록은 조회·수정·삭제·연산 어디에도 섞이지 않는다. */
class UserIsolationIT extends AbstractIntegrationTest {

    private static final String SLEEP_BODY = """
            {"samples":[
              {"clientUuid":"u1","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
              {"clientUuid":"u2","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T07:30:00+09:00","hkValue":3}
            ]}""";

    @MockitoBean
    Clock clock;

    private long addRecord(String accessToken, int amountMg, String timestamp) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/caffeine-records")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%d,"drinkName":"커피","timestamp":"%s"}""".formatted(amountMg, timestamp)))
                .andExpect(status().isCreated()).andReturn();
        return dataOf(created).get("id").asLong();
    }

    private void uploadSleep(String accessToken, int expectedInserted) throws Exception {
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(SLEEP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inserted").value(expectedInserted));
    }

    @Test
    void 다른사용자의_카페인기록은_수정_삭제시_404이고_원본은_그대로() throws Exception {
        String owner = authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");
        long id = addRecord(owner, 150, "2026-06-01T09:00:00+09:00");

        mockMvc.perform(put("/api/caffeine-records/" + id).header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1,"drinkName":"변조","timestamp":"2026-06-01T10:00:00+09:00"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));
        mockMvc.perform(delete("/api/caffeine-records/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + owner)
                        .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].amount").value(150))
                .andExpect(jsonPath("$.data[0].drinkName").value("커피"));
    }

    @Test
    void 카페인_목록과_today는_본인기록만_반영() throws Exception {
        String owner = authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");
        addRecord(owner, 300, "2026-06-01T09:00:00+09:00");

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + other)
                        .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + other)
                        .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayTotal").value(0))
                .andExpect(jsonPath("$.data.currentResidual").value(0.0));
    }

    @Test
    void 수면_같은clientUuid도_사용자별로_저장되고_요약은_본인것만() throws Exception {
        String owner = authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");
        uploadSleep(owner, 2);

        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + other)
                        .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasData").value(false));

        // 멱등 키는 (user_id, client_uuid) — 다른 사용자의 같은 UUID 는 중복으로 보지 않는다
        uploadSleep(other, 2);
    }

    @Test
    void 학습_관측과_입력데이터는_사용자별() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-06-02T00:00:00Z"));
        String owner = authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");
        addRecord(owner, 200, "2026-05-31T19:00:00+09:00");
        uploadSleep(owner, 2);

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + owner)
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1));

        mockMvc.perform(get("/api/learning/observations").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
        // 다른 사용자의 수면 데이터로 학습하지 않는다
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + other)
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skipReason").value("NO_SLEEP_SUMMARY"));
    }

    @Test
    void 설정변경은_다른사용자에_영향없음() throws Exception {
        String owner = authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"halfLife":7.0,"condition":1,"bedtimeHour":1,"bedtimeMinute":30,\
                                "referenceDoseMg":200,"isLearningEnabled":false,\
                                "notifications":{"cutoff":false,"bedtimeResidual":false,"recordReminder":false}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.halfLife").value(5.0))
                .andExpect(jsonPath("$.data.bedtimeHour").value(23))
                .andExpect(jsonPath("$.data.isLearningEnabled").value(true))
                .andExpect(jsonPath("$.data.notifications.cutoff").value(true));
    }

    @Test
    void 로그아웃은_다른사용자의_토큰에_영향없음() throws Exception {
        signup("owner@b.com", DEFAULT_PASSWORD, "주인");
        signup("other@b.com", DEFAULT_PASSWORD, "타인");
        String ownerAccess = login("owner@b.com", DEFAULT_PASSWORD).get("accessToken").asText();
        String otherRefresh = login("other@b.com", DEFAULT_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + ownerAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"any\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + otherRefresh + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void me는_토큰주인의_정보만_돌려줌() throws Exception {
        authToken("owner@b.com", "주인");
        String other = authToken("other@b.com", "타인");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("other@b.com"));
    }
}
