package com.jongbeom.server.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc 통합 테스트 공통 베이스 — 어노테이션 스택 + 회원가입/로그인 헬퍼.
 * H2(test 프로파일)로 동작하고 각 테스트는 @Transactional 롤백으로 격리된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class AbstractIntegrationTest {

    protected static final String DEFAULT_PASSWORD = "password1!";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;

    /** 회원가입(201 기대). */
    protected MvcResult signup(String email, String password, String nickname) throws Exception {
        String body = """
                {"email":"%s","password":"%s","nickname":"%s"}""".formatted(email, password, nickname);
        return mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    /** 로그인(200 기대) 후 토큰 응답 JSON. */
    protected JsonNode login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 회원가입 + 로그인 후 accessToken 반환 — 도메인 IT 의 표준 준비 단계. */
    protected String authToken(String email, String nickname) throws Exception {
        signup(email, DEFAULT_PASSWORD, nickname);
        return login(email, DEFAULT_PASSWORD).get("accessToken").asText();
    }
}
