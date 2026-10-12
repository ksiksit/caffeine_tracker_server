package com.jongbeom.server.e2e;

import static com.jongbeom.server.e2e.E2eApi.KST;
import static com.jongbeom.server.e2e.E2eApi.TZ;
import static com.jongbeom.server.e2e.E2eApi.api;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 MySQL 에서만 드러나는 동작. 통합 테스트는 H2(MySQL 호환 모드)라 아래 차이를 볼 수 없다.
 * <ul>
 *   <li>문자셋: utf8mb4 가 아니면 4바이트 문자(이모지)가 깨지거나 저장에 실패한다</li>
 *   <li>DATETIME(6): 마이크로초까지 왕복해야 한다</li>
 *   <li>collation utf8mb4_unicode_ci: 문자열 비교가 대소문자를 구분하지 않는다(H2 는 구분)</li>
 * </ul>
 */
class MySqlBehaviorE2ETest {

    @BeforeAll
    static void localOnly() {
        assumeTrue(E2eEnvironment.isLocal(), "쓰기 시나리오는 local(빈 DB) 모드에서만 실행한다");
    }

    private static String signupAndLogin(String nickname) {
        String email = E2eApi.uniqueEmail("mysql");
        E2eApi.signup(email, nickname);
        return E2eApi.login(email, E2eApi.PASSWORD).getString("accessToken");
    }

    @Test
    void 이모지와_한글이_깨지지_않고_왕복한다() {
        // 🧋(U+1F9CB)는 UTF-8 4바이트 — utf8(3바이트) 컬럼이면 INSERT 가 실패한다. ☕ 같은 3바이트 문자로는 검증이 안 된다.
        // 닉네임은 다시 읽는 API 가 없으므로 가입(INSERT) 성공까지만, DB 왕복은 음료명으로 확인한다.
        String access = signupAndLogin("카페인🧋러버");

        String today = LocalDate.now(KST).toString();
        api(access).body("""
                        {"amount":150,"drinkName":"🧋 흑당 버블티","timestamp":"%sT09:00:00+09:00"}""".formatted(today))
                .when().post("/api/caffeine-records")
                .then().statusCode(201);
        api(access).queryParam("now", today + "T14:00:00+09:00").queryParam("tz", TZ)
                .when().get("/api/caffeine-records")
                .then().statusCode(200).body("data[0].drinkName", equalTo("🧋 흑당 버블티"));
    }

    @Test
    void 섭취시각은_마이크로초까지_UTC로_왕복한다() {
        String access = signupAndLogin("정밀도");
        LocalDate today = LocalDate.now(KST);
        api(access).body("""
                        {"amount":100,"drinkName":"커피","timestamp":"%sT09:00:00.123456+09:00"}""".formatted(today))
                .when().post("/api/caffeine-records")
                .then().statusCode(201)
                .body("data.timestamp", equalTo(today + "T00:00:00.123456Z"));

        api(access).queryParam("now", today + "T14:00:00+09:00").queryParam("tz", TZ)
                .when().get("/api/caffeine-records")
                .then().statusCode(200)
                .body("data[0].timestamp", equalTo(today + "T00:00:00.123456Z")); // DB 를 거쳐 다시 읽은 값
    }

    @Test
    void 이메일은_대소문자를_구분하지_않는다() {
        // 현재 동작 고정: 앱은 이메일을 정규화하지 않고 DB collation(_ci)에 맡긴다 → MySQL 에서는 대소문자 무시.
        // 같은 테스트를 H2(대소문자 구분)에서 돌리면 두 번째 가입이 성공한다 — E2E 로만 확인할 수 있는 차이.
        String lower = E2eApi.uniqueEmail("case");
        String upper = lower.toUpperCase();
        E2eApi.signup(lower, "대소문자");

        api().body("""
                        {"email":"%s","password":"%s","nickname":"대문자"}""".formatted(upper, E2eApi.PASSWORD))
                .when().post("/api/auth/signup")
                .then().statusCode(409).body("error.code", equalTo("EMAIL_ALREADY_EXISTS"));

        api().body("""
                        {"email":"%s","password":"%s"}""".formatted(upper, E2eApi.PASSWORD))
                .when().post("/api/auth/login")
                .then().statusCode(200);
    }
}
