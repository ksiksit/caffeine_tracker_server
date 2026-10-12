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
    void caffeine_today_건강상태_배수가_잔량에_반영된다() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        createCaffeineRecord(accessToken, 100, "2026-06-01T09:00:00+09:00"); // now 14:00 → 5h 경과
        String settings = """
                {"halfLife":5.0,"condition":%d,"bedtimeHour":23,"bedtimeMinute":0,                "referenceDoseMg":75,"isLearningEnabled":true,                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}""";

        // 유효 반감기 = 5h × 배수 → 잔량 = 100·2^(-5/유효반감기)
        Object[][] cases = {
                {0, 5.0, 50.0},                // 없음 ×1.0
                {1, 2.5, 25.0},                // 흡연 ×0.5
                {2, 10.0, 70.71067811865476},  // 경구피임약 ×2.0
                {3, 12.5, 75.7858283255199},   // 임신 ×2.5
        };
        for (Object[] c : cases) {
            mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(settings.formatted((int) c[0])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.effectiveHalfLifeHours").value((double) c[1]));

            mockMvc.perform(get("/api/caffeine/today").header("Authorization", "Bearer " + accessToken)
                            .param("now", "2026-06-01T14:00:00+09:00")
                            .param("tz", "Asia/Seoul"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentResidual", Matchers.closeTo((double) c[2], 1e-6)));
        }
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
    void caffeine_오늘기록목록은_05시이후만_시각오름차순_UTC로() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        createCaffeineRecord(accessToken, 50, "2026-06-01T04:30:00+09:00");  // 05:00 이전 → 어제 하루
        createCaffeineRecord(accessToken, 200, "2026-06-01T10:00:00+09:00");
        createCaffeineRecord(accessToken, 100, "2026-06-01T08:00:00+09:00");

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                        .param("now", "2026-06-01T14:00:00+09:00")
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].amount").value(100))
                .andExpect(jsonPath("$.data[0].timestamp").value("2026-05-31T23:00:00Z")) // 응답은 UTC Instant
                .andExpect(jsonPath("$.data[1].amount").value(200))
                .andExpect(jsonPath("$.data[1].drinkName").value("커피"))
                .andExpect(jsonPath("$.data[1].id").isNumber());
    }

    @Test
    void caffeine_오늘기록목록_기록없으면_빈배열() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                        .param("now", "2026-06-01T14:00:00+09:00")
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void caffeine_기록_검증_카페인량은_1에서_10000() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        String body = """
                {"amount":%d,"drinkName":"커피","timestamp":"2026-06-01T09:00:00+09:00"}""";

        for (int invalid : new int[]{0, 10001}) {
            mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("amount"));
        }
        for (int valid : new int[]{1, 10000}) {
            mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(valid)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void caffeine_기록_검증_음료명_필수_시각_필수() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100,"drinkName":" ","timestamp":"2026-06-01T09:00:00+09:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("drinkName"));

        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100,"drinkName":"커피"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("timestamp"));
    }

    @Test
    void caffeine_수정_검증실패면_400이고_원본은_그대로() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        long id = createCaffeineRecord(accessToken, 150, "2026-06-01T09:00:00+09:00");

        mockMvc.perform(put("/api/caffeine-records/" + id).header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0,"drinkName":"콜라","timestamp":"2026-06-01T10:00:00+09:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                        .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(jsonPath("$.data[0].amount").value(150));
    }

    @Test
    void caffeine_없는기록_수정은_404() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        mockMvc.perform(put("/api/caffeine-records/999999").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":80,"drinkName":"콜라","timestamp":"2026-06-01T10:00:00+09:00"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CAFFEINE_RECORD_NOT_FOUND"));
    }

    @Test
    void settings_검증_경계값() throws Exception {
        String accessToken = authToken("c@b.com", "테스터");
        String body = """
                {"halfLife":%s,"condition":%d,"bedtimeHour":%d,"bedtimeMinute":0,                "referenceDoseMg":%d,"isLearningEnabled":true,                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}""";

        // 필드별 범위 밖 값 → 그 필드로 VALIDATION_FAILED
        Object[][] invalid = {
                {"2.9", 0, 23, 75, "halfLife"},
                {"7.1", 0, 23, 75, "halfLife"},
                {"5.0", 4, 23, 75, "condition"},
                {"5.0", 0, 24, 75, "bedtimeHour"},
                {"5.0", 0, 23, 0, "referenceDoseMg"},
                {"5.0", 0, 23, 1001, "referenceDoseMg"},
        };
        for (Object[] c : invalid) {
            mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(c[0], c[1], c[2], c[3])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value(c[4]));
        }

        // 경계값 자체는 허용(반감기 3.0·7.0, 건강상태 3, 기준용량 1000)
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("3.0", 3, 0, 1000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.halfLife").value(3.0));
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("7.0", 0, 23, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.halfLife").value(7.0));
    }

    @Test
    void 인증없이_접근하면_401() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/caffeine/today")
                .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/caffeine-records")
                .param("now", "2026-06-01T14:00:00+09:00").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/caffeine-records/1"))
                .andExpect(status().isUnauthorized());
    }
}
