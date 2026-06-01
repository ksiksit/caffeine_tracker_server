package com.jongbeom.server.learning;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** learning 도메인 통합 테스트: run→관측·settings 갱신, 멱등, 2박 순차 prior 체이닝(#1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LearningControllerIT {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockBean
    Clock clock;

    private String token() throws Exception {
        // today = 2026-06-02 KST 고정 → 직전 7일이 후보
        given(clock.instant()).willReturn(Instant.parse("2026-06-02T00:00:00Z"));
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"learn@b.com\",\"password\":\"password1!\",\"nickname\":\"학습\"}"))
                .andExpect(status().isCreated());
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"learn@b.com\",\"password\":\"password1!\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void caffeine(String t, int mg, String tsKst) throws Exception {
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"amount\":%d,\"drinkName\":\"커피\",\"timestamp\":\"%s+09:00\"}", mg, tsKst)))
                .andExpect(status().isCreated());
    }

    /** inBed bedtime~+30m, core +30m~+8h30m (SOL 30분). */
    private void sleepNight(String t, String prefix, String inBedStartKst, String coreEndKst) throws Exception {
        String body = String.format("""
                {"samples":[
                  {"clientUuid":"%s-bed","start":"%sT23:00:00+09:00","end":"%sT23:30:00+09:00","hkValue":0},
                  {"clientUuid":"%s-core","start":"%sT23:30:00+09:00","end":"%s+09:00","hkValue":3}
                ]}""", prefix, inBedStartKst, inBedStartKst, prefix, inBedStartKst, coreEndKst);
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void run_단일night_관측생성_settings갱신_그리고_멱등() throws Exception {
        String t = token();
        caffeine(t, 200, "2026-05-31T19:00:00");            // 취침 4h 전
        sleepNight(t, "n1", "2026-05-31", "2026-06-01T07:30:00"); // 밤 → 2026-06-01 아침

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + t)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        // settings 갱신 확인(prior 5.0 → 변동, lastLearnedDate 설정)
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learnedVariance").value(org.hamcrest.Matchers.lessThan(2.25)))
                .andExpect(jsonPath("$.lastLearnedDate").value("2026-06-01"));

        mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.observations[0].observedSolMinutes").value(30.0))
                .andExpect(jsonPath("$.latest.posteriorMean").isNumber());

        // 재실행 → 멱등(updated 0, ALREADY_LEARNED)
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + t)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.skipReason").value("ALREADY_LEARNED"));
    }

    @Test
    void run_2박_순차prior체이닝() throws Exception {
        String t = token();
        // Night A → 2026-05-31 아침
        caffeine(t, 200, "2026-05-30T19:00:00");
        sleepNight(t, "a", "2026-05-30", "2026-05-31T07:30:00");
        // Night B → 2026-06-01 아침
        caffeine(t, 200, "2026-05-31T19:00:00");
        sleepNight(t, "b", "2026-05-31", "2026-06-01T07:30:00");

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + t)
                .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));

        // 오래된→최신 순. obs[1].priorMean == obs[0].posteriorMean (순차 체이닝, #1)
        MvcResult dash = mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andReturn();
        var obs = objectMapper.readTree(dash.getResponse().getContentAsString()).get("observations");
        double post0 = obs.get(0).get("posteriorMean").asDouble();
        double prior1 = obs.get(1).get("priorMean").asDouble();
        org.assertj.core.api.Assertions.assertThat(prior1).isCloseTo(post0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void 학습비활성이면_skip() throws Exception {
        String t = token();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/settings").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"halfLife\":5.0,\"condition\":0,\"bedtimeHour\":23,\"bedtimeMinute\":0,"
                        + "\"referenceDoseMg\":75,\"isLearningEnabled\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + t)
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
