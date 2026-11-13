package com.jongbeom.server.domain.insight.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jongbeom.server.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** insight 통합 테스트: 기간 검증, 섭취 통계 요약 규칙, 리포트 표본 부족·충분, 밤 제외, 사용자 격리. */
class InsightControllerIT extends AbstractIntegrationTest {

    @MockitoBean
    Clock clock;

    /** 오늘 = 2026-06-15 (KST 12:00). */
    @BeforeEach
    void stubToday() {
        given(clock.instant()).willReturn(Instant.parse("2026-06-15T03:00:00Z"));
    }

    private ResultActions call(String token, String path, String from, String to) throws Exception {
        return mockMvc.perform(get("/api/insight/" + path).header("Authorization", "Bearer " + token)
                .param("from", from).param("to", to).param("tz", "Asia/Seoul"));
    }

    /** 밤(아침 날짜 = bedDate + 1): bedDate 23:00 inBed, solMinutes 뒤 core 시작 ~ 다음 날 07:00. */
    private void sleepNight(String token, String bedDate, String nextDate, int solMinutes) throws Exception {
        String asleepAt = "23:%02d:00".formatted(solMinutes);
        uploadSleepSamples(token, """
                [{"clientUuid":"%s-bed","start":"%sT23:00:00+09:00","end":"%sT%s+09:00","hkValue":0},
                 {"clientUuid":"%s-core","start":"%sT%s+09:00","end":"%sT07:00:00+09:00","hkValue":3}]"""
                .formatted(bedDate, bedDate, bedDate, asleepAt, bedDate, bedDate, asleepAt, nextDate));
    }

    // ---------------------------------------------------------------- 기간 검증

    @Test
    void 기간_검증_실패는_400_INVALID_PARAMETER() throws Exception {
        String token = authToken("insight@b.com", "인사이트");
        for (String path : new String[] {"intake-stats", "caffeine-sleep"}) {
            call(token, path, "2026-06-10", "2026-06-09").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
            call(token, path, "2026-05-14", "2026-06-14").andExpect(status().isBadRequest())   // 32일
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
            call(token, path, "2026-06-10", "2026-06-16").andExpect(status().isBadRequest())   // 내일
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
            call(token, path, "2026-05-16", "2026-06-15").andExpect(status().isOk());          // 31일, to = 오늘
            mockMvc.perform(get("/api/insight/" + path).header("Authorization", "Bearer " + token)
                            .param("from", "2026-06-10").param("to", "2026-06-15").param("tz", "Not/AZone"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        }
    }

    @Test
    void 토큰_없으면_401() throws Exception {
        mockMvc.perform(get("/api/insight/intake-stats")
                        .param("from", "2026-06-10").param("to", "2026-06-15").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 섭취 통계

    @Test
    void 섭취통계_빈날은_0_요약은_기록있는날만_오늘제외() throws Exception {
        String token = authToken("insight@b.com", "인사이트");
        createCaffeineRecord(token, 100, "2026-06-12T10:00:00+09:00");
        createCaffeineRecord(token, 150, "2026-06-13T09:00:00+09:00");
        createCaffeineRecord(token, 300, "2026-06-13T20:00:00+09:00");
        createCaffeineRecord(token, 50, "2026-06-14T03:00:00+09:00");   // 05:00 전 → 섭취일 06-13
        createCaffeineRecord(token, 900, "2026-06-15T09:00:00+09:00");  // 오늘 → 요약 제외

        JsonNode data = dataOf(call(token, "intake-stats", "2026-06-12", "2026-06-15")
                .andExpect(status().isOk()).andReturn());
        assertThat(data.get("dayCount").asInt()).isEqualTo(4);
        assertThat(data.get("recordedDayCount").asInt()).isEqualTo(3);
        assertThat(data.get("averageDailyMg").asDouble()).isEqualTo(300.0);   // (100 + 500) / 2
        assertThat(data.get("maxDailyMg").asDouble()).isEqualTo(500.0);
        assertThat(data.get("overLimitDayCount").asInt()).isEqualTo(1);
        // 10:00(05:00 기준 300분)과 03:00(1320분) → 평균 810분 → 18:30
        assertThat(data.get("averageLastIntakeMinuteOfDay").asInt()).isEqualTo(18 * 60 + 30);
        assertThat(data.get("summarySentence").asText())
                .isEqualTo("최근 4일 하루 평균 300mg, 400mg을 넘긴 날은 1일이에요. 마지막 한 잔은 평균 18시 30분이에요.");

        JsonNode days = data.get("days");
        assertThat(days).hasSize(4);
        assertThat(days.get(1).get("date").asText()).isEqualTo("2026-06-13");
        assertThat(days.get(1).get("totalMg").asDouble()).isEqualTo(500.0);
        assertThat(days.get(1).get("recordCount").asInt()).isEqualTo(3);
        assertThat(days.get(1).get("firstIntakeAt").asText()).isEqualTo("2026-06-13T00:00:00Z");
        assertThat(days.get(1).get("lastIntakeAt").asText()).isEqualTo("2026-06-13T18:00:00Z");
        assertThat(days.get(1).get("overLimit").asBoolean()).isTrue();
        assertThat(days.get(2).get("date").asText()).isEqualTo("2026-06-14");
        assertThat(days.get(2).get("totalMg").asDouble()).isZero();
        assertThat(days.get(2).get("recordCount").asInt()).isZero();
        assertThat(days.get(2).get("firstIntakeAt").isNull()).isTrue();
        assertThat(days.get(3).get("totalMg").asDouble()).isEqualTo(900.0);
    }

    /** 검증 재현: 00:00 KST 500mg. 04:59 에는 섭취일 11/13 이 끝나지 않아 요약에서 빠지고, 05:00 부터 들어간다. */
    @Test
    void 섭취통계_0459까지는_달력_전날이_끝나지_않은_날() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-11-13T19:59:00Z"));   // 11/14 04:59 KST
        String token = authToken("insight@b.com", "인사이트");
        createCaffeineRecord(token, 100, "2026-11-12T15:00:00+09:00");
        createCaffeineRecord(token, 500, "2026-11-14T00:00:00+09:00");   // 섭취일 11/13

        // 검증 상한은 달력 날짜 그대로: to = 11/14 허용, 11/15 는 400
        call(token, "intake-stats", "2026-11-06", "2026-11-15").andExpect(status().isBadRequest());
        JsonNode before = dataOf(call(token, "intake-stats", "2026-11-06", "2026-11-14")
                .andExpect(status().isOk()).andReturn());
        assertThat(before.get("recordedDayCount").asInt()).isEqualTo(2);
        assertThat(before.get("averageDailyMg").asDouble()).isEqualTo(100.0);
        assertThat(before.get("maxDailyMg").asDouble()).isEqualTo(100.0);
        assertThat(before.get("overLimitDayCount").asInt()).isZero();
        assertThat(before.get("summarySentence").asText()).startsWith("최근 9일 ");
        // to 가 섭취일 기준 오늘(11/13)이어도 "최근"
        assertThat(dataOf(call(token, "intake-stats", "2026-11-06", "2026-11-13")
                .andExpect(status().isOk()).andReturn()).get("summarySentence").asText()).startsWith("최근 8일 ");

        given(clock.instant()).willReturn(Instant.parse("2026-11-13T20:00:00Z"));   // 11/14 05:00 KST
        JsonNode after = dataOf(call(token, "intake-stats", "2026-11-06", "2026-11-14")
                .andExpect(status().isOk()).andReturn());
        assertThat(after.get("averageDailyMg").asDouble()).isEqualTo(300.0);
        assertThat(after.get("maxDailyMg").asDouble()).isEqualTo(500.0);
        assertThat(after.get("overLimitDayCount").asInt()).isEqualTo(1);
        // 이제 to = 11/13 은 끝난 과거 기간
        assertThat(dataOf(call(token, "intake-stats", "2026-11-06", "2026-11-13")
                .andExpect(status().isOk()).andReturn()).get("summarySentence").asText()).startsWith("11월 6일~11월 13일 ");
    }

    @Test
    void 섭취통계_과거기간_기록없음() throws Exception {
        String token = authToken("insight@b.com", "인사이트");
        JsonNode data = dataOf(call(token, "intake-stats", "2026-06-01", "2026-06-07")
                .andExpect(status().isOk()).andReturn());
        assertThat(data.get("recordedDayCount").asInt()).isZero();
        assertThat(data.get("averageDailyMg").isNull()).isTrue();
        assertThat(data.get("maxDailyMg").isNull()).isTrue();
        assertThat(data.get("averageLastIntakeMinuteOfDay").isNull()).isTrue();
        assertThat(data.get("summarySentence").asText()).isEqualTo("6월 1일~6월 7일 하루 평균을 낼 섭취 기록이 아직 없어요.");
        assertThat(data.get("days")).hasSize(7);
    }

    // ---------------------------------------------------------------- 리포트

    @Test
    void 리포트_표본부족이어도_nights는_내리고_inBed없는_밤과_다른_사용자_데이터는_빠진다() throws Exception {
        String other = authToken("other@b.com", "다른");
        createCaffeineRecord(other, 300, "2026-06-13T19:00:00+09:00");
        sleepNight(other, "2026-06-12", "2026-06-13", 30);

        String token = authToken("insight@b.com", "인사이트");
        createCaffeineRecord(token, 200, "2026-06-13T19:00:00+09:00");
        sleepNight(token, "2026-06-13", "2026-06-14", 30);
        // 06-15 아침 밤: inBed 없음 → 제외
        uploadSleepSamples(token, """
                [{"clientUuid":"nobed","start":"2026-06-14T23:30:00+09:00","end":"2026-06-15T07:00:00+09:00","hkValue":3}]""");

        JsonNode data = dataOf(call(token, "caffeine-sleep", "2026-06-09", "2026-06-15")
                .andExpect(status().isOk()).andReturn());
        assertThat(data.get("nightCount").asInt()).isEqualTo(1);
        assertThat(data.get("sufficient").asBoolean()).isFalse();
        assertThat(data.get("residualThresholdMg").asDouble()).isEqualTo(50.0);
        double halfLife = data.get("halfLifeHoursUsed").asDouble();
        assertThat(halfLife).isEqualTo(5.0);
        assertThat(data.get("headlines").get(0).asText()).isEqualTo("리포트를 보려면 수면 기록이 있는 밤이 4밤 더 필요해요");

        JsonNode night = data.get("nights").get(0);
        assertThat(night.get("date").asText()).isEqualTo("2026-06-14");
        assertThat(night.get("bedtime").asText()).isEqualTo("2026-06-13T14:00:00Z");
        assertThat(night.get("residualAtBedtimeMg").asDouble()).isCloseTo(200 * Math.pow(2, -4 / halfLife), within(1e-9));
        assertThat(night.get("dayTotalMg").asDouble()).isEqualTo(200.0);
        assertThat(night.get("lastIntakeAt").asText()).isEqualTo("2026-06-13T10:00:00Z");
        assertThat(night.get("cutoffKept").asBoolean()).isTrue();
        assertThat(night.get("solMinutes").asDouble()).isEqualTo(30.0);
        assertThat(night.get("totalSleepMinutes").asDouble()).isEqualTo(450.0);
        assertThat(night.get("sleepEfficiency").asDouble()).isCloseTo(450.0 / 480, within(1e-9));
        assertThat(night.get("deepSleepRatio").asDouble()).isZero();

        JsonNode residual = data.get("residualComparison");
        assertThat(residual.get("sufficient").asBoolean()).isFalse();
        assertThat(residual.get("moreNightsNeeded").asInt()).isEqualTo(5);
        assertThat(residual.get("solDiffMinutes").isNull()).isTrue();
        assertThat(residual.get("high").get("nightCount").asInt()).isEqualTo(1);
        assertThat(residual.get("low").get("avgSolMinutes").isNull()).isTrue();
        assertThat(residual.get("sentence").asText()).isEqualTo("비교하려면 잔량이 높았던 밤이 2밤, 낮았던 밤이 3밤 더 필요해요");
        assertThat(data.get("cutoffComparison").get("sentence").asText())
                .isEqualTo("지금 기준 용량(75mg)으로 따지면, 마감을 넘긴 밤이 3밤, 지킨 밤이 2밤 더 있어야 비교할 수 있어요");

        // 같은 밤의 수면 요약과 SOL 이 같다(§3.4 계약)
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + token)
                        .param("date", "2026-06-14").param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data.sleepOnsetLatencySeconds").value(1800.0));

        JsonNode otherData = dataOf(call(other, "caffeine-sleep", "2026-06-09", "2026-06-15").andReturn());
        assertThat(otherData.get("nightCount").asInt()).isEqualTo(1);
        assertThat(otherData.get("nights").get(0).get("date").asText()).isEqualTo("2026-06-13");
    }

    @Test
    void 리포트_6밤_잔량비교_차이있음_문장과_headlines() throws Exception {
        String token = authToken("insight@b.com", "인사이트");
        // 06-09 ~ 06-11 아침: 전날 19:00 200mg(취침 잔량 ≈ 115mg), SOL 40분
        for (int day = 8; day <= 10; day++) {
            createCaffeineRecord(token, 200, "2026-06-%02dT19:00:00+09:00".formatted(day));
            sleepNight(token, "2026-06-%02d".formatted(day), "2026-06-%02d".formatted(day + 1), 40);
        }
        // 06-12 ~ 06-14 아침: 카페인 없음, SOL 10분
        for (int day = 11; day <= 13; day++) {
            sleepNight(token, "2026-06-%02d".formatted(day), "2026-06-%02d".formatted(day + 1), 10);
        }

        JsonNode data = dataOf(call(token, "caffeine-sleep", "2026-06-09", "2026-06-15")
                .andExpect(status().isOk()).andReturn());
        assertThat(data.get("nightCount").asInt()).isEqualTo(6);
        assertThat(data.get("sufficient").asBoolean()).isTrue();

        JsonNode residual = data.get("residualComparison");
        assertThat(residual.get("sufficient").asBoolean()).isTrue();
        assertThat(residual.get("moreNightsNeeded").isNull()).isTrue();
        assertThat(residual.get("solDiffMinutes").asDouble()).isCloseTo(30.0, within(1e-9));
        // 효율: 440/480 − 470/480 = −0.0625
        assertThat(residual.get("efficiencyDiff").asDouble()).isCloseTo(-30.0 / 480, within(1e-9));
        String expected = "취침 때 카페인이 50mg 이상 남아 있던 밤은 잠드는 데 평균 30분 더 걸렸고, 수면 효율은 6%p 낮았어요 (3밤 vs 3밤)";
        assertThat(residual.get("sentence").asText()).isEqualTo(expected);

        // 비교 B: 섭취 있는 밤 3밤 모두 마감(≈20:04) 전 → 지킴 3, 넘김 0
        JsonNode cutoff = data.get("cutoffComparison");
        assertThat(cutoff.get("sufficient").asBoolean()).isFalse();
        assertThat(cutoff.get("low").get("nightCount").asInt()).isEqualTo(3);
        assertThat(cutoff.get("high").get("nightCount").asInt()).isZero();
        assertThat(cutoff.get("moreNightsNeeded").asInt()).isEqualTo(3);
        assertThat(cutoff.get("sentence").asText())
                .isEqualTo("지금 기준 용량(75mg)으로 따지면, 마감을 넘긴 밤이 3밤 더 있어야 비교할 수 있어요");

        assertThat(data.get("headlines")).hasSize(1);
        assertThat(data.get("headlines").get(0).asText()).isEqualTo(expected);
        JsonNode nights = data.get("nights");
        assertThat(nights).hasSize(6);
        assertThat(nights.get(0).get("date").asText()).isEqualTo("2026-06-09");
        assertThat(nights.get(5).get("residualAtBedtimeMg").asDouble()).isZero();
        assertThat(nights.get(5).get("cutoffKept").isNull()).isTrue();
        assertThat(nights.get(5).get("dayTotalMg").asDouble()).isZero();
    }
}
