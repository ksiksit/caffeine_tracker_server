package com.jongbeom.server.e2e;

import static com.jongbeom.server.e2e.E2eApi.KST;
import static com.jongbeom.server.e2e.E2eApi.TZ;
import static com.jongbeom.server.e2e.E2eApi.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.restassured.path.json.JsonPath;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 스모크: 서버가 떠 있고 핵심 경로가 응답하는지. local·external(운영) 양쪽에서 돈다.
 * 운영 데이터를 남기지 않도록 읽기 위주다(설정 조회는 최초 1회 기본값 행을 만들 수 있음).
 * external 모드에서는 미리 만들어 둔 전용 계정을 E2E_EMAIL·E2E_PASSWORD 로 받는다 — 레포에 저장하지 않는다.
 */
class SmokeE2ETest {

    private static String email;
    private static String password;

    @BeforeAll
    static void account() {
        if (E2eEnvironment.isLocal()) {
            email = E2eApi.uniqueEmail("smoke");
            password = E2eApi.PASSWORD;
            E2eApi.signup(email, "스모크");
        } else {
            email = System.getenv("E2E_EMAIL");
            password = System.getenv("E2E_PASSWORD");
        }
    }

    private static void requireAccount() {
        assumeTrue(email != null && password != null,
                "external 모드에서 로그인 스모크를 하려면 E2E_EMAIL·E2E_PASSWORD 가 필요하다");
    }

    @Test
    void 헬스체크는_UP() {
        api().when().get("/actuator/health")
                .then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void 로그인후_내정보_설정_오늘잔량을_조회한다() {
        requireAccount();
        String access = E2eApi.login(email, password).getString("accessToken");

        api(access).when().get("/api/me")
                .then().statusCode(200).body("data.email", equalTo(email));
        api(access).when().get("/api/settings")
                .then().statusCode(200).body("data.effectiveHalfLifeHours", greaterThanOrEqualTo(1.5));
        api(access)
                .queryParam("now", OffsetDateTime.now(KST).toString())
                .queryParam("tz", TZ)
                .when().get("/api/caffeine/today")
                .then().statusCode(200).body("data.todayTotal", greaterThanOrEqualTo(0));
    }

    @Test
    void 리프레시토큰으로_재발급한_액세스토큰이_동작한다() {
        // 운영 DB 에 token_version 컬럼이 빠졌다면(ALTER 누락) 서버가 아예 뜨지 않거나 여기서 실패한다
        requireAccount();
        String refresh = E2eApi.login(email, password).getString("refreshToken");

        JsonPath reissued = api().body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/auth/refresh")
                .then().statusCode(200)
                .extract().jsonPath().setRootPath("data");
        assertThat(reissued.getString("tokenType")).isEqualTo("Bearer");

        api(reissued.getString("accessToken")).when().get("/api/me")
                .then().statusCode(200).body("data.email", equalTo(email));
    }
}
