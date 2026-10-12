package com.jongbeom.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** auth 도메인 통합 테스트: 가입→로그인→me, 검증 실패, 토큰 재발급·종류 분리, 로그아웃(토큰 버전) 무효화. */
class AuthControllerIT extends AbstractIntegrationTest {

    private static String refreshTokenBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    @Test
    void signup_201_login_200_그리고_me_조회_200() throws Exception {
        MvcResult signupResult = signup("a@b.com", "password1!", "테스터");
        long userId = dataOf(signupResult).get("userId").asLong();

        JsonNode tokens = login("a@b.com", "password1!");
        String accessToken = tokens.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.email").value("a@b.com"));
    }

    @Test
    void login_잘못된자격증명이면_401_INVALID_CREDENTIALS() throws Exception {
        signup("a@b.com", "password1!", "테스터");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@b.com","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void signup_빈이메일이면_400_VALIDATION_FAILED_fieldErrors_포함() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":"password1!","nickname":"테스터"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors").isArray());
    }

    @Test
    void signup_중복이메일이면_409_EMAIL_ALREADY_EXISTS() throws Exception {
        signup("a@b.com", "password1!", "테스터");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@b.com","password":"password2!","nickname":"다른사람"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void signup_검증_경계값() throws Exception {
        String body = """
                {"email":"%s","password":"%s","nickname":"%s"}""";
        String[][] invalid = {
                {"not-an-email", "password1!", "테스터", "email"},
                {"a@b.com", "1234567", "테스터", "password"},        // 8자 미만
                {"a@b.com", "x".repeat(73), "테스터", "password"},   // 72자 초과(BCrypt 입력 한계)
                {"a@b.com", "password1!", "한", "nickname"},          // 2자 미만
                {"a@b.com", "password1!", "가".repeat(21), "nickname"}, // 20자 초과
        };
        for (String[] c : invalid) {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(c[0], c[1], c[2])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value(c[3]));
        }

        // 경계값 자체(비밀번호 8자·닉네임 2자)는 허용
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("a@b.com", "12345678", "테스")))
                .andExpect(status().isCreated());
    }

    @Test
    void login_존재하지않는이메일도_401_INVALID_CREDENTIALS() throws Exception {
        // 이메일 존재 여부를 노출하지 않도록 비밀번호 불일치와 같은 응답
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@b.com","password":"password1!"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refresh_성공시_새토큰_발급_그리고_회전이없어_기존토큰도_유효() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        JsonNode initial = login("a@b.com", "password1!");
        String oldRefresh = initial.get("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(oldRefresh)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reissued = dataOf(refreshResult);
        assertThat(reissued.get("accessToken").asText()).isNotBlank();
        assertThat(reissued.get("refreshToken").asText()).isNotBlank();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + reissued.get("accessToken").asText()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(oldRefresh)))
                .andExpect(status().isOk());
    }

    @Test
    void refreshToken으로_API를_호출하면_401() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        String refreshToken = login("a@b.com", "password1!").get("refreshToken").asText();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessToken으로_refresh하면_401_INVALID_REFRESH_TOKEN() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        String accessToken = login("a@b.com", "password1!").get("accessToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refresh_존재하지않는토큰이면_401_INVALID_REFRESH_TOKEN() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody("non-existent-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_200_그리고_같은_refreshToken으로_refresh시_401() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        JsonNode tokens = login("a@b.com", "password1!");
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout후_다시_로그인한_refreshToken은_정상_재발급된다() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        String accessToken = login("a@b.com", "password1!").get("accessToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody("any")))
                .andExpect(status().isOk());

        String newRefresh = login("a@b.com", "password1!").get("refreshToken").asText();
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(newRefresh)))
                .andExpect(status().isOk());
    }

    @Test
    void logout_인증없이_호출하면_401() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody("any")))
                .andExpect(status().isUnauthorized());
    }
}
