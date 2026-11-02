package com.jongbeom.server.e2e;

import static com.jongbeom.server.e2e.E2eApi.KST;
import static com.jongbeom.server.e2e.E2eApi.TZ;
import static com.jongbeom.server.e2e.E2eApi.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 사용자 여정 E2E: 가입부터 로그아웃까지 실제 이미지·실제 MySQL·실제 HTTP 로.
 * 통합 테스트(UserJourneyIT)와 시나리오는 비슷하지만, 여기서는 시계를 고정할 수 없다 —
 * 서버는 진짜 현재 시각으로 학습 후보 날짜를 정하므로 테스트 데이터도 "오늘" 기준 상대 날짜로 만든다.
 * 데이터를 쓰므로 local 모드에서만 돈다.
 */
class UserJourneyE2ETest {

    @BeforeAll
    static void localOnly() {
        assumeTrue(E2eEnvironment.isLocal(), "쓰기 시나리오는 local(빈 DB) 모드에서만 실행한다");
    }

    @Test
    void 가입부터_기록_수면_학습_알림_재발급_로그아웃까지() {
        LocalDate today = LocalDate.now(KST);
        LocalDate yesterday = today.minusDays(1);
        // 오늘 연산·알림은 now 파라미터를 받으므로 하루 중 실제 시각과 무관하게 오늘 14:00 으로 고정한다
        String nowParam = today + "T14:00:00+09:00";

        // 1. 가입·로그인
        String email = E2eApi.uniqueEmail("journey");
        E2eApi.signup(email, "여정");
        JsonPath tokens = E2eApi.login(email, E2eApi.PASSWORD);
        String access = tokens.getString("accessToken");
        String refresh = tokens.getString("refreshToken");

        // 2. 설정 저장
        api(access).body("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":75,"isLearningEnabled":true,\
                        "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}""")
                .when().put("/api/settings")
                .then().statusCode(200).body("data.effectiveHalfLifeHours", equalTo(5.0));

        // 3. 오늘 13:00 100mg → 14:00 잔량 = 100·2^(-1/5)
        createRecord(access, 100, today + "T13:00:00+09:00");
        api(access).queryParam("now", nowParam).queryParam("tz", TZ)
                .when().get("/api/caffeine/today")
                .then().statusCode(200)
                .body("data.todayTotal", equalTo(100))
                .body("data.currentResidual", closeTo(87.05505632961241, 1e-6));

        // 4. 어제 저녁 커피 + 어젯밤 수면(23:00 취침·23:30 잠듦·07:30 기상) — 같은 배치 재업로드는 멱등
        createRecord(access, 200, yesterday + "T19:00:00+09:00");
        String samples = """
                {"samples":[
                  {"clientUuid":"bed","start":"%1$sT23:00:00+09:00","end":"%1$sT23:30:00+09:00","hkValue":0},
                  {"clientUuid":"core","start":"%1$sT23:30:00+09:00","end":"%2$sT07:30:00+09:00","hkValue":3}]}"""
                .formatted(yesterday, today);
        api(access).body(samples).when().post("/api/sleep/samples")
                .then().statusCode(200).body("data.inserted", equalTo(2));
        api(access).body(samples).when().post("/api/sleep/samples")
                .then().statusCode(200).body("data.inserted", equalTo(0)); // MySQL UNIQUE(user_id, client_uuid)

        api(access).queryParam("date", today.toString()).queryParam("tz", TZ)
                .when().get("/api/sleep/summary")
                .then().statusCode(200)
                .body("data.hasData", equalTo(true))
                .body("data.sleepOnsetLatencySeconds", equalTo(1800.0));

        // 5. 학습 — 서버의 실제 시계로 오늘을 정하고, 어젯밤(→ 오늘 아침)을 학습한다
        api(access).queryParam("tz", TZ)
                .when().post("/api/learning/run")
                .then().statusCode(200).body("data.updatedCount", equalTo(1));
        JsonPath settings = api(access).when().get("/api/settings")
                .then().statusCode(200).extract().jsonPath().setRootPath("data");
        double learnedMean = settings.getDouble("learnedMean");
        assertThat(learnedMean).isNotEqualTo(5.0).isBetween(4.5, 5.5);
        assertThat(settings.getString("lastLearnedDate")).isEqualTo(today.toString());
        api(access).when().get("/api/learning/dashboard")
                .then().statusCode(200).body("data.count", equalTo(1));

        // 6. 학습 후 오늘 잔량은 학습된 반감기로 계산된다
        api(access).queryParam("now", nowParam).queryParam("tz", TZ)
                .when().get("/api/caffeine/today")
                .then().statusCode(200)
                .body("data.currentResidual", closeTo(100 * Math.pow(2, -1 / learnedMean), 1e-6));

        // 7. 알림 계획: 지금 이후·시각 오름차순
        List<String> fireAts = api(access).queryParam("now", nowParam).queryParam("tz", TZ)
                .when().get("/api/notifications/plan")
                .then().statusCode(200)
                .extract().jsonPath().getList("data.items.fireAt", String.class);
        List<Instant> fireTimes = fireAts.stream().map(Instant::parse).toList();
        assertThat(fireTimes).isSorted()
                .allMatch(t -> t.isAfter(Instant.parse(today + "T05:00:00Z")));

        // 8. 재발급 → 로그아웃 → 모든 리프레시 토큰 무효
        String newRefresh = api().body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/auth/refresh")
                .then().statusCode(200).extract().path("data.refreshToken");
        api(access).body("{\"refreshToken\":\"" + newRefresh + "\"}")
                .when().post("/api/auth/logout")
                .then().statusCode(200);
        for (String token : List.of(refresh, newRefresh)) {
            api().body("{\"refreshToken\":\"" + token + "\"}")
                    .when().post("/api/auth/refresh")
                    .then().statusCode(401).body("error.code", equalTo("INVALID_REFRESH_TOKEN"));
        }
    }

    @Test
    void 다른사용자의_기록은_수정할수없다() {
        String alice = signupAndLogin("alice");
        String bob = signupAndLogin("bob");
        long aliceRecord = createRecord(alice, 150, LocalDate.now(KST) + "T09:00:00+09:00");

        api(bob).body("""
                        {"amount":1,"drinkName":"탈취","timestamp":"%sT10:00:00+09:00"}""".formatted(LocalDate.now(KST)))
                .when().put("/api/caffeine-records/" + aliceRecord)
                .then().statusCode(404).body("error.code", equalTo("CAFFEINE_RECORD_NOT_FOUND"));
        api(alice).queryParam("now", LocalDate.now(KST) + "T14:00:00+09:00").queryParam("tz", TZ)
                .when().get("/api/caffeine-records")
                .then().statusCode(200).body("data", hasSize(1)).body("data[0].amount", equalTo(150));
    }

    private static String signupAndLogin(String prefix) {
        String email = E2eApi.uniqueEmail(prefix);
        E2eApi.signup(email, prefix + "님");
        return E2eApi.login(email, E2eApi.PASSWORD).getString("accessToken");
    }

    private static long createRecord(String access, int amount, String timestamp) {
        Number id = api(access).body("""
                        {"amount":%d,"drinkName":"커피","timestamp":"%s"}""".formatted(amount, timestamp))
                .when().post("/api/caffeine-records")
                .then().statusCode(201).extract().path("data.id");
        return id.longValue();
    }
}
