package com.jongbeom.server.auth.controller;

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

/** auth 도메인 통합 테스트: 가입→로그인→me, 검증 실패, 토큰 회전·재사용 차단, 로그아웃. */
class AuthControllerIT extends AbstractIntegrationTest {

    private static String refreshTokenBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    @Test
    void signup_201_login_200_그리고_me_조회_200() throws Exception {
        MvcResult signupResult = signup("a@b.com", "password1!", "테스터");
        JsonNode signupBody = objectMapper.readTree(signupResult.getResponse().getContentAsString());
        long userId = signupBody.get("userId").asLong();

        JsonNode tokens = login("a@b.com", "password1!");
        String accessToken = tokens.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.userId").value((int) userId))
                .andExpect(jsonPath("$.email").value("a@b.com"));
    }

    @Test
    void login_잘못된자격증명이면_401_INVALID_CREDENTIALS() throws Exception {
        signup("a@b.com", "password1!", "테스터");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@b.com","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void signup_빈이메일이면_400_VALIDATION_FAILED_fieldErrors_포함() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":"password1!","nickname":"테스터"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void refresh_성공_새토큰_발급_그리고_기존토큰_재사용시_401() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        JsonNode initial = login("a@b.com", "password1!");
        String oldRefresh = initial.get("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(oldRefresh)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rotated = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertThat(rotated.get("accessToken").asText()).isNotBlank();
        assertThat(rotated.get("refreshToken").asText())
                .isNotBlank()
                .isNotEqualTo(oldRefresh);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(oldRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refresh_존재하지않는토큰이면_401_INVALID_REFRESH_TOKEN() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody("non-existent-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_204_그리고_같은_refreshToken으로_refresh시_401() throws Exception {
        signup("a@b.com", "password1!", "테스터");
        JsonNode tokens = login("a@b.com", "password1!");
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_인증없이_호출하면_401() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody("any")))
                .andExpect(status().isUnauthorized());
    }
}
