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

    @Test
    void observations_학습전엔_빈배열_학습후엔_관측1건() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");

        mockMvc.perform(get("/api/learning/observations").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00");
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                .param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data.updatedCount").value(1));

        // 첫 학습이라 prior 는 기본값(5.0h, 2.25). 관측 SOL 30분, 날짜는 수면이 끝난 아침
        mockMvc.perform(get("/api/learning/observations").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].date").value("2026-06-01"))
                .andExpect(jsonPath("$.data[0].observedSolMinutes").value(30.0))
                .andExpect(jsonPath("$.data[0].priorMean").value(5.0))
                .andExpect(jsonPath("$.data[0].posteriorVariance").value(Matchers.lessThan(2.25)))
                .andExpect(jsonPath("$.data[0].predictedResidualAtBedtime").value(Matchers.greaterThan(0.0)));
    }

    @Test
    void 다른사용자의_학습관측은_보이지_않는다() throws Exception {
        stubToday();
        String alice = authToken("alice@b.com", "앨리스");
        String bob = authToken("bob@b.com", "보브");
        caffeine(alice, 200, "2026-05-31T19:00:00");
        sleepNight(alice, "n1", "2026-05-31", "2026-06-01T07:30:00");
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + alice)
                .param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data.updatedCount").value(1));

        mockMvc.perform(get("/api/learning/observations").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/api/learning/dashboard").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
        // 밥은 수면 기록이 없으므로 앨리스 데이터로 학습되지 않는다
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + bob)
                .param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skipReason").value("NO_SLEEP_SUMMARY"));
    }

    // ---- skip 사유 (INVALID_PRIOR·INVALID_OBSERVATION 은 설정 검증·앞단 게이트 때문에 API 로 도달 불가 — 방어 코드) ----

    private void run(String accessToken, int expectedUpdated, String expectedSkipReason) throws Exception {
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(expectedUpdated))
                .andExpect(expectedSkipReason == null
                        ? jsonPath("$.data.skipReason").value(Matchers.nullValue())
                        : jsonPath("$.data.skipReason").value(expectedSkipReason));
    }

    /** 2026-05-31 밤(→ 06-01 아침) 단일 샘플 업로드. 시각은 KST 로컬. */
    private void sample(String accessToken, String uuid, String startKst, String endKst, int hkValue)
            throws Exception {
        uploadSleepSamples(accessToken, """
                [{"clientUuid":"%s","start":"%s+09:00","end":"%s+09:00","hkValue":%d}]"""
                .formatted(uuid, startKst, endKst, hkValue));
    }

    @Test
    void skip_수면이_4시간미만이면_SLEEP_TOO_SHORT() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sample(accessToken, "bed", "2026-05-31T23:00:00", "2026-05-31T23:30:00", 0);
        sample(accessToken, "core", "2026-05-31T23:30:00", "2026-06-01T02:30:00", 3); // 3h

        run(accessToken, 0, "SLEEP_TOO_SHORT");
    }

    @Test
    void skip_기록된_취침부터_잠들기까지_180분초과면_INVALID_SOL() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sample(accessToken, "bed", "2026-05-31T22:00:00", "2026-06-01T02:00:00", 0);
        sample(accessToken, "core", "2026-06-01T02:00:00", "2026-06-01T10:00:00", 3); // SOL 240분

        run(accessToken, 0, "INVALID_SOL");
    }

    @Test
    void skip_취침기록없고_설정취침시각으로도_SOL범위밖이면_MISSING_BEDTIME() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        // inBed 없음, 설정 취침 23:00 → 03:30 잠듦까지 270분 → 폴백 불가
        sample(accessToken, "core", "2026-06-01T03:30:00", "2026-06-01T11:30:00", 3);

        run(accessToken, 0, "MISSING_BEDTIME");
    }

    @Test
    void skip_카페인기록이_없으면_INSUFFICIENT_CAFFEINE_RESIDUAL() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00");

        run(accessToken, 0, "INSUFFICIENT_CAFFEINE_RESIDUAL");
    }

    @Test
    void skip_취침_24시간보다_이전_섭취는_학습에_쓰지_않는다() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // 취침(05-31 23:00) 25시간 전 200mg — 포함됐다면 잔량 6.25mg(>1mg)로 학습됐을 양
        caffeine(accessToken, 200, "2026-05-30T22:00:00");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00");

        run(accessToken, 0, "INSUFFICIENT_CAFFEINE_RESIDUAL");
    }

    @Test
    void skip_최근7일보다_오래된_수면은_후보가_아니다() throws Exception {
        stubToday(); // today 06-02 → 후보 05-27 ~ 06-02
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-25T19:00:00");
        sleepNight(accessToken, "old", "2026-05-25", "2026-05-26T07:30:00"); // → 05-26 아침

        run(accessToken, 0, "NO_SLEEP_SUMMARY");
    }

    @Test
    void 여러밤중_하나라도_학습되면_사유는_보고하지_않는다() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // 05-30 밤: 카페인 없음 → 실패
        sleepNight(accessToken, "a", "2026-05-30", "2026-05-31T07:30:00");
        // 05-31 밤: 정상 → 성공
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T07:30:00");

        run(accessToken, 1, null);
    }

    @Test
    void 모두_실패하면_가장_최근_밤의_사유를_보고한다() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        // 05-30 밤(→05-31): 수면 3h → SLEEP_TOO_SHORT
        caffeine(accessToken, 200, "2026-05-30T19:00:00");
        sample(accessToken, "a-bed", "2026-05-30T23:00:00", "2026-05-30T23:30:00", 0);
        sample(accessToken, "a-core", "2026-05-30T23:30:00", "2026-05-31T02:30:00", 3);
        // 05-31 밤(→06-01): 정상 수면이지만 24h 안 카페인 없음 → INSUFFICIENT_CAFFEINE_RESIDUAL
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T07:30:00");

        run(accessToken, 0, "INSUFFICIENT_CAFFEINE_RESIDUAL");
    }

    // ---- 학습 결과가 실제 연산에 반영되는지 ----

    @Test
    void 학습결과는_유효반감기에_반영되고_학습을_끄면_수동반감기로_돌아간다() throws Exception {
        stubToday();
        String accessToken = authToken("learn@b.com", "학습");
        caffeine(accessToken, 200, "2026-05-31T19:00:00");
        sleepNight(accessToken, "n1", "2026-05-31", "2026-06-01T07:30:00");
        run(accessToken, 1, null);

        MvcResult learned = mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        double learnedMean = dataOf(learned).get("learnedMean").asDouble();
        assertThat(learnedMean).isNotEqualTo(5.0);
        // 학습 ON: 유효 반감기 = learnedMean × 배수(NONE=1.0)
        assertThat(dataOf(learned).get("effectiveHalfLifeHours").asDouble()).isCloseTo(learnedMean, within(1e-9));

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,                                "referenceDoseMg":75,"isLearningEnabled":false,                                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}"""))
                .andExpect(status().isOk())
                // 학습 OFF: 유효 반감기 = 수동 halfLife
                .andExpect(jsonPath("$.data.effectiveHalfLifeHours").value(5.0));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(post("/api/learning/run").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/learning/observations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/learning/dashboard"))
                .andExpect(status().isUnauthorized());
    }
}
