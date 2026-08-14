package com.jongbeom.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jongbeom.server.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/** learning 도메인 통합 테스트: run→관측·settings 갱신, 멱등, 2박 순차 prior 체이닝(#1). */
class LearningControllerIT extends AbstractIntegrationTest {

    @MockitoBean
    Clock clock;

    /** today = 2026-06-02 KST 고정 → 직전 7일이 학습 후보가 된다. 각 테스트 시작 시 호출. */
    private void stubToday() {
        given(clock.instant()).willReturn(Instant.parse("2026-06-02T00:00:00Z"));
    }

    private void caffeine(String accessToken, int mg, String tsKst) throws Exception {
        String body = """
                {"amount":%d,"drinkName":"커피","timestamp":"%s+09:00"}""".formatted(mg, tsKst);
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());
    }

    /** inBed bedtime~+30m, core +30m~+8h30m (SOL 30분). */
    private void sleepNight(String accessToken, String prefix, String inBedStartKst, String coreEndKst)
            throws Exception {
        String body = """
                {"samples":[
                  {"clientUuid":"%s-bed","start":"%sT23:00:00+09:00","end":"%sT23:30:00+09:00","hkValue":0},
                  {"clientUuid":"%s-core","start":"%sT23:30:00+09:00","end":"%s+09:00","hkValue":3}
                ]}""".formatted(prefix, inBedStartKst, inBedStartKst, prefix, inBedStartKst, coreEndKst);
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void run_단일night_관측생성_settings갱신_그리고_멱등() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");            // 취침 4h 전
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00"); // 밤 → 2026-06-01 아침

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        // settings 갱신 확인(prior 분산 2.25=1.5² 에서 감소, lastLearnedDate 설정)
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learnedVariance").value(Matchers.lessThan(2.25)))
                .andExpect(jsonPath("$.lastLearnedDate").value("2026-06-01"));

        mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.observations[0].observedSolMinutes").value(30.0))
                .andExpect(jsonPath("$.latest.posteriorMean").isNumber());

        // 재실행 → 멱등(updated 0, ALREADY_LEARNED)
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.skipReason").value("ALREADY_LEARNED"));
    }

    @Test
    void run_2박_순차prior체이닝() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // Night A → 2026-05-31 아침
        caffeine(accessToken, 200, "2026-05-30T19:00:00");
        sleepNight(accessToken, "a", "2026-05-30", "2026-05-31T07:30:00");
        // Night B → 2026-06-01 아침
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T07:30:00");

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));

        // 오래된→최신 순. obs[1].priorMean == obs[0].posteriorMean (순차 체이닝, #1)
        MvcResult dash = mockMvc.perform(get("/api/learning/dashboard")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andReturn();
        JsonNode observations = objectMapper.readTree(dash.getResponse().getContentAsString())
                .get("observations");
        double firstPosteriorMean = observations.get(0).get("posteriorMean").asDouble();
        double secondPriorMean = observations.get(1).get("priorMean").asDouble();
        assertThat(secondPriorMean).isCloseTo(firstPosteriorMean, within(1e-9));
    }

    @Test
    void 학습비활성이면_skip() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":75,"isLearningEnabled":false}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.skipReason").value("LEARNING_DISABLED"));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(post("/api/learning/run").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
