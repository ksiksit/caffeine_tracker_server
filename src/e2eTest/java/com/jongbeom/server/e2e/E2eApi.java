package com.jongbeom.server.e2e;

import static io.restassured.RestAssured.given;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import java.time.ZoneId;
import java.util.UUID;

/**
 * E2E 공용 요청 빌더·헬퍼. 서버 내부 클래스(DTO 등)는 쓰지 않는다 — 앱 바깥의 클라이언트처럼 JSON 문자열만 주고받는다.
 * MockMvc(서버 안에서 가짜 요청)와 달리 RestAssured 는 진짜 HTTP 를 네트워크로 보낸다.
 */
public final class E2eApi {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String TZ = "Asia/Seoul";
    public static final String PASSWORD = "e2e-password1!";

    /** JSON 소수는 기본이 Float 라 정밀도가 깎인다 — Double 로 받는다. */
    private static final RestAssuredConfig CONFIG = RestAssuredConfig.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.DOUBLE));

    private E2eApi() {
    }

    /** 인증 없는 JSON 요청. */
    public static RequestSpecification api() {
        return given().spec(new RequestSpecBuilder()
                .setBaseUri(E2eEnvironment.baseUrl())
                .setContentType(ContentType.JSON)
                .setConfig(CONFIG)
                .build());
    }

    /** Bearer 토큰을 붙인 JSON 요청. */
    public static RequestSpecification api(String accessToken) {
        return api().header("Authorization", "Bearer " + accessToken);
    }

    /** 실행마다 겹치지 않는 이메일 — 같은 DB 에 여러 번 돌려도 충돌하지 않게. */
    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@e2e.test";
    }

    public static void signup(String email, String nickname) {
        api().body("""
                        {"email":"%s","password":"%s","nickname":"%s"}""".formatted(email, PASSWORD, nickname))
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    /** 로그인 응답의 data(accessToken·refreshToken …). */
    public static JsonPath login(String email, String password) {
        return api().body("""
                        {"email":"%s","password":"%s"}""".formatted(email, password))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().jsonPath().setRootPath("data");
    }
}
