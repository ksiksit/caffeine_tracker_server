package com.jongbeom.server;

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
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사용자 시나리오 통합 테스트: 여러 도메인을 실제 앱 사용 순서대로 이어 호출해, 한 단계의 결과가
 * 다음 단계의 입력으로 제대로 흘러가는지 본다(가입→설정→기록→수면→학습→오늘 연산→알림→토큰 갱신→로그아웃).
 * 각 도메인의 세부 분기는 도메인별 IT 가 맡고, 여기서는 연결만 확인한다.
 */
class UserJourneyIT extends AbstractIntegrationTest {

    private static final String TZ = "Asia/Seoul";

    /** 학습 후보 날짜 계산용(today). 오늘 연산·알림 계획은 now 파라미터를 쓴다. */
    @MockitoBean
    Clock clock;

    private void today(String instant) {
        given(clock.instant()).willReturn(Instant.parse(instant));
    }

    /** {@code night}(KST 날짜) 23:00 취침, 23:30 잠듦(SOL 30분), 다음날 07:30 기상. */
    private void sleepNight(String accessToken, String night, String nextMorning) throws Exception {
        uploadSleepSamples(accessToken, """
                [{"clientUuid":"%1$s-bed","start":"%1$sT23:00:00+09:00","end":"%1$sT23:30:00+09:00","hkValue":0},
                 {"clientUuid":"%1$s-core","start":"%1$sT23:30:00+09:00","end":"%2$sT07:30:00+09:00","hkValue":3}]"""
                .formatted(night, nextMorning));
    }

    private JsonNode getData(String accessToken, String path, String... params) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + accessToken);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return dataOf(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    @Test
    void 가입부터_학습_오늘연산_알림_토큰갱신_로그아웃까지() throws Exception {
        today("2026-06-02T00:00:00Z"); // = 06-02 09:00 KST

        // 1. 가입·로그인 → 내 정보
        signup("journey@b.com", DEFAULT_PASSWORD, "여정");
        JsonNode tokens = login("journey@b.com", DEFAULT_PASSWORD);
        String access = tokens.get("accessToken").asText();
        String refresh = tokens.get("refreshToken").asText();
        assertThat(getData(access, "/api/me").get("email").asText()).isEqualTo("journey@b.com");

        // 2. 첫 설정 조회는 기본값(반감기 5h), 취침 23:00·기준 75mg·학습 ON 으로 저장
        assertThat(getData(access, "/api/settings").get("effectiveHalfLifeHours").asDouble()).isEqualTo(5.0);
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                                "referenceDoseMg":75,"isLearningEnabled":true,\
                                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}"""))
                .andExpect(status().isOk());

        // 3. 어제(05-31) 저녁 커피 + 그날 밤 수면 → 06-01 아침 수면 요약
        createCaffeineRecord(access, 200, "2026-05-31T19:00:00+09:00");
        sleepNight(access, "2026-05-31", "2026-06-01");
        JsonNode summary = getData(access, "/api/sleep/summary", "date", "2026-06-01", "tz", TZ);
        assertThat(summary.get("hasData").asBoolean()).isTrue();
        assertThat(summary.get("sleepOnsetLatencySeconds").asDouble()).isEqualTo(1800.0);

        // 4. 학습 → 설정의 학습 상태와 대시보드에 반영
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + access).param("tz", TZ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1));
        JsonNode settings = getData(access, "/api/settings");
        double learnedMean = settings.get("learnedMean").asDouble();
        assertThat(learnedMean).isNotEqualTo(5.0).isBetween(4.5, 5.5); // trust region ±0.5h
        assertThat(settings.get("lastLearnedDate").asText()).isEqualTo("2026-06-01");
        assertThat(settings.get("effectiveHalfLifeHours").asDouble()).isCloseTo(learnedMean, within(1e-9));

        JsonNode dashboard = getData(access, "/api/learning/dashboard");
        assertThat(dashboard.get("count").asInt()).isEqualTo(1);
        assertThat(dashboard.get("latest").get("posteriorMean").asDouble()).isCloseTo(learnedMean, within(1e-9));

        // 5. 오늘(06-02) 09:00 100mg → 14:00 잔량은 학습된 반감기로 계산된다
        createCaffeineRecord(access, 100, "2026-06-02T09:00:00+09:00");
        JsonNode todayResult = getData(access, "/api/caffeine/today",
                "now", "2026-06-02T14:00:00+09:00", "tz", TZ);
        assertThat(todayResult.get("todayTotal").asInt()).isEqualTo(100); // 어제 200mg 은 오늘 합계에 없음
        assertThat(todayResult.get("currentResidual").asDouble())
                .isCloseTo(100 * Math.pow(2, -5.0 / learnedMean), within(1e-6));

        JsonNode records = getData(access, "/api/caffeine-records", "now", "2026-06-02T14:00:00+09:00", "tz", TZ);
        assertThat(records).hasSize(1);

        // 6. 알림 계획: 켜진 종류만, now 이후, fireAt 오름차순. 오늘 기록이 있으니 리마인더는 없다
        JsonNode plan = getData(access, "/api/notifications/plan", "now", "2026-06-02T14:00:00+09:00", "tz", TZ);
        List<Instant> fireTimes = new ArrayList<>();
        for (JsonNode item : plan.get("items")) {
            assertThat(item.get("type").asText()).isNotEqualTo("RECORD_REMINDER");
            fireTimes.add(Instant.parse(item.get("fireAt").asText()));
        }
        assertThat(fireTimes).isNotEmpty().isSorted()
                .allMatch(t -> t.isAfter(Instant.parse("2026-06-02T05:00:00Z")));
        assertThat(plan.get("items").get(0).get("type").asText()).isEqualTo("CUTOFF");

        // 7. 토큰 갱신 → 새 access 로 계속 사용
        JsonNode reissued = dataOf(mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
        String newAccess = reissued.get("accessToken").asText();
        String newRefresh = reissued.get("refreshToken").asText();
        assertThat(getData(newAccess, "/api/settings").get("learnedMean").asDouble())
                .isCloseTo(learnedMean, within(1e-9));

        // 8. 로그아웃 → 모든 refresh 무효. access 는 만료(1h)까지 유효 — 서버 무상태 JWT 의 의도된 한계
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + newAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isOk());
        for (String token : new String[]{refresh, newRefresh}) {
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + token + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        }
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    @Test
    void 매일_학습하면_새로운_밤만_추가되고_prior가_이어진다() throws Exception {
        String access = authToken("daily@b.com", "매일");

        // Day 1 (06-01 아침): 05-31 밤 학습
        today("2026-06-01T00:00:00Z");
        createCaffeineRecord(access, 200, "2026-05-31T19:00:00+09:00");
        sleepNight(access, "2026-05-31", "2026-06-01");
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + access).param("tz", TZ))
                .andExpect(jsonPath("$.data.updatedCount").value(1));
        double afterDay1 = getData(access, "/api/settings").get("learnedMean").asDouble();

        // Day 2 (06-02 아침): 06-01 밤만 새로 학습, 이미 학습한 05-31 밤은 건너뜀
        today("2026-06-02T00:00:00Z");
        createCaffeineRecord(access, 200, "2026-06-01T19:00:00+09:00");
        sleepNight(access, "2026-06-01", "2026-06-02");
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + access).param("tz", TZ))
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.skipReason").value(Matchers.nullValue()));

        JsonNode observations = getData(access, "/api/learning/observations");
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).get("date").asText()).isEqualTo("2026-06-01");
        assertThat(observations.get(1).get("date").asText()).isEqualTo("2026-06-02");
        // 둘째 날의 prior 는 첫째 날 학습 결과(하루 사이 실행이 분리돼도 체이닝 유지)
        assertThat(observations.get(1).get("priorMean").asDouble()).isCloseTo(afterDay1, within(1e-9));

        JsonNode settings = getData(access, "/api/settings");
        assertThat(settings.get("lastLearnedDate").asText()).isEqualTo("2026-06-02");
        assertThat(settings.get("learnedMean").asDouble())
                .isCloseTo(observations.get(1).get("posteriorMean").asDouble(), within(1e-9));
        // 관측을 쌓을수록 불확실성은 줄어든다
        assertThat(settings.get("learnedVariance").asDouble())
                .isLessThan(observations.get(0).get("posteriorVariance").asDouble());
    }
}
