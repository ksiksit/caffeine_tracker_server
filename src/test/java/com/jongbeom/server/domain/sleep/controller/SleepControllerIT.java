package com.jongbeom.server.domain.sleep.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jongbeom.server.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** sleep 도메인 통합 테스트: 멱등 업로드와 서버 병합·SOL 계산. */
class SleepControllerIT extends AbstractIntegrationTest {

    @Test
    void 업로드_멱등_그리고_요약_SOL계산() throws Exception {
        String accessToken = authToken("sleep@b.com", "수면");

        // 2026-05-31 밤 KST: inBed 23:00~23:30, core 23:30~다음날, → SOL 30분
        // 윈도우(date=2026-06-01): 2026-05-31 18:00 ~ 2026-06-01 12:00 KST
        String body = """
                {"samples":[
                  {"clientUuid":"u1","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
                  {"clientUuid":"u2","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T05:00:00+09:00","hkValue":3}
                ]}""";

        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(2))
                .andExpect(jsonPath("$.data.inserted").value(2));

        // 같은 배치 재업로드 → 멱등(inserted 0)
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inserted").value(0));

        // 요약: SOL 1800초, 총수면=core 5.5h, 단계 2개
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + accessToken)
                .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasData").value(true))
                .andExpect(jsonPath("$.data.sleepOnsetLatencySeconds").value(1800.0))
                .andExpect(jsonPath("$.data.totalSleepSeconds").value(Matchers.closeTo(5.5 * 3600, 1.0)))
                .andExpect(jsonPath("$.data.records", Matchers.hasSize(2)));
    }

    @Test
    void 데이터없으면_hasData_false() throws Exception {
        String accessToken = authToken("sleep@b.com", "수면");
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + accessToken)
                .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasData").value(false))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void 업로드_검증_hkValue는_0에서_5_clientUuid는_필수() throws Exception {
        String accessToken = authToken("sleep@b.com", "수면");
        String body = """
                {"samples":[{"clientUuid":"%s","start":"2026-05-31T23:00:00+09:00",                "end":"2026-05-31T23:30:00+09:00","hkValue":%d}]}""";

        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("u1", 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("samples[0].hkValue"));

        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("", 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("samples[0].clientUuid"));

        // 검증 실패한 배치는 하나도 저장되지 않는다
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data.hasData").value(false));
    }

    @Test
    void 같은배치_안의_중복UUID는_한번만_저장된다() throws Exception {
        String accessToken = authToken("sleep@b.com", "수면");
        JsonNode result = uploadSleepSamples(accessToken, """
                [{"clientUuid":"dup","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
                 {"clientUuid":"dup","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0}]""");
        assertThat(result.get("received").asInt()).isEqualTo(2);
        assertThat(result.get("inserted").asInt()).isEqualTo(1);
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(get("/api/sleep/summary").param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
