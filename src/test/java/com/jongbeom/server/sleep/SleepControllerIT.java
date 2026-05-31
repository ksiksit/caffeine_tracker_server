package com.jongbeom.server.sleep;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SleepControllerIT {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    private String token() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sleep@b.com\",\"password\":\"password1!\",\"nickname\":\"수면\"}"))
                .andExpect(status().isCreated());
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sleep@b.com\",\"password\":\"password1!\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void 업로드_멱등_그리고_요약_SOL계산() throws Exception {
        String t = token();

        // 2026-05-31 밤 KST: inBed 23:00~23:30, core 23:30~다음날, → SOL 30분
        // 윈도우(date=2026-06-01): 2026-05-31 18:00 ~ 2026-06-01 12:00 KST
        String body = """
                {"samples":[
                  {"clientUuid":"u1","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
                  {"clientUuid":"u2","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T05:00:00+09:00","hkValue":3}
                ]}""";

        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.inserted").value(2));

        // 같은 배치 재업로드 → 멱등(inserted 0)
        mockMvc.perform(post("/api/sleep/samples").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(0));

        // 요약: SOL 1800초, 총수면=core 5.5h, 단계 2개
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + t)
                .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasData").value(true))
                .andExpect(jsonPath("$.sleepOnsetLatencySeconds").value(1800.0))
                .andExpect(jsonPath("$.totalSleepSeconds").value(Matchers.closeTo(5.5 * 3600, 1.0)))
                .andExpect(jsonPath("$.records", Matchers.hasSize(2)));
    }

    @Test
    void 데이터없으면_hasData_false() throws Exception {
        String t = token();
        mockMvc.perform(get("/api/sleep/summary").header("Authorization", "Bearer " + t)
                .param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasData").value(false))
                .andExpect(jsonPath("$.records").isEmpty());
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(get("/api/sleep/summary").param("date", "2026-06-01").param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
