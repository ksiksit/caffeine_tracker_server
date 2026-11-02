package com.jongbeom.server.domain.learning.controller;

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
                .andExpect(jsonPath("$.data.updatedCount").value(1));

        // settings 갱신 확인(prior 분산 2.25=1.5² 에서 감소, lastLearnedDate 설정)
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learnedVariance").value(Matchers.lessThan(2.25)))
                .andExpect(jsonPath("$.data.lastLearnedDate").value("2026-06-01"));

        mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.observations[0].observedSolMinutes").value(30.0))
                .andExpect(jsonPath("$.data.latest.posteriorMean").isNumber());

        // 재실행 → 멱등(updated 0, ALREADY_LEARNED)
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skipReason").value("ALREADY_LEARNED"));
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
                .andExpect(jsonPath("$.data.updatedCount").value(2));

        // 오래된→최신 순. obs[1].priorMean == obs[0].posteriorMean (순차 체이닝, #1)
        MvcResult dash = mockMvc.perform(get("/api/learning/dashboard")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andReturn();
        JsonNode observations = dataOf(dash).get("observations");
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
                        "referenceDoseMg":75,"isLearningEnabled":false,\
                        "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skipReason").value("LEARNING_DISABLED"));
    }

    private void uploadSamples(String accessToken, String samplesJson) throws Exception {
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"samples\":[" + samplesJson + "]}"))
                .andExpect(status().isOk());
    }

    private void expectSkip(String accessToken, String skipReason) throws Exception {
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skipReason").value(skipReason))
                .andExpect(jsonPath("$.data.skipMessage").isNotEmpty());
    }

    @Test
    void 수면데이터없으면_NO_SLEEP_SUMMARY() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        expectSkip(accessToken, "NO_SLEEP_SUMMARY");
    }

    @Test
    void 최근7일_밖의_night는_후보아님_NO_SLEEP_SUMMARY() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // today 06-02 → 후보 05-27~06-02. 05-26 아침에 끝나는 밤은 제외
        caffeine(accessToken, 200, "2026-05-25T19:00:00");
        sleepNight(accessToken, "old", "2026-05-25", "2026-05-26T07:30:00");
        expectSkip(accessToken, "NO_SLEEP_SUMMARY");
    }

    @Test
    void 수면4시간미만이면_SLEEP_TOO_SHORT() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T02:30:00"); // core 3h
        expectSkip(accessToken, "SLEEP_TOO_SHORT");
    }

    @Test
    void SOL이_180분초과면_INVALID_SOL() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T17:00:00");
        // inBed 20:00 시작, 잠은 23:30부터 → SOL 210분
        uploadSamples(accessToken, """
                {"clientUuid":"bed","start":"2026-05-31T20:00:00+09:00","end":"2026-05-31T20:30:00+09:00","hkValue":0},
                {"clientUuid":"core","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T07:30:00+09:00","hkValue":3}""");
        expectSkip(accessToken, "INVALID_SOL");
    }

    @Test
    void 취침전_카페인없으면_INSUFFICIENT_CAFFEINE_RESIDUAL() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00");
        expectSkip(accessToken, "INSUFFICIENT_CAFFEINE_RESIDUAL");
    }

    @Test
    void 여러밤_모두실패면_가장최신_night의_사유() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // 05-31 아침: 수면 3h → SLEEP_TOO_SHORT / 06-01 아침: 카페인 없음 → INSUFFICIENT_CAFFEINE_RESIDUAL
        sleepNight(accessToken, "a", "2026-05-30", "2026-05-31T02:30:00");
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T07:30:00");
        expectSkip(accessToken, "INSUFFICIENT_CAFFEINE_RESIDUAL");
    }

    @Test
    void 하나라도_학습되면_skip사유_없음() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // 05-31 아침: 학습 성공 / 06-01 아침(더 최신): 수면 3h → 실패
        caffeine(accessToken, 200, "2026-05-30T19:00:00");
        sleepNight(accessToken, "a", "2026-05-30", "2026-05-31T07:30:00");
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T02:30:00");

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.skipReason").value(Matchers.nullValue()));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(post("/api/learning/run").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
