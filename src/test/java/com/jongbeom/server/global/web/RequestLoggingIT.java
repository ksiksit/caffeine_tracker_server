package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/** 요청 로그 — 한 요청당 한 줄, MDC(requestId·userId), 시큐리티 401 포함, 이메일 미기록. */
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingIT extends AbstractIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing(); // MDC 는 지연 조회라 요청 스레드에 값이 있을 때 고정한다
            super.append(event);
        }
    };

    @BeforeEach
    void attachAppender() {
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
    }

    @Test
    void 인증된_요청은_requestId와_userId를_함께_남긴다() throws Exception {
        String accessToken = authToken("logging@b.com", "로그");
        appender.list.clear(); // 준비 단계의 회원가입·로그인 요청 제외

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage())
                .matches("요청 완료 method=GET, path=/api/me, status=200, durationMs=\\d+");
        assertThat(event.getMDCPropertyMap().get(RequestLoggingFilter.REQUEST_ID_KEY)).matches(UUID_PATTERN);
        assertThat(event.getMDCPropertyMap().get(UserIdMdcFilter.USER_ID_KEY)).matches("\\d+");
    }

    @Test
    void 토큰없는_401도_userId_없이_남긴다() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).contains("path=/api/settings, status=401");
        assertThat(event.getMDCPropertyMap().get(RequestLoggingFilter.REQUEST_ID_KEY)).matches(UUID_PATTERN);
        assertThat(event.getMDCPropertyMap()).doesNotContainKey(UserIdMdcFilter.USER_ID_KEY);
    }

    @Test
    void 쿼리스트링은_남기지_않는다() throws Exception {
        mockMvc.perform(get("/api/settings?tz=Asia/Seoul")).andExpect(status().isUnauthorized());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("tz=", "Asia/Seoul");
    }

    @Test
    void 요청이_끝나면_MDC를_비운다() throws Exception {
        String accessToken = authToken("mdc@b.com", "엠디씨");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(MDC.get(RequestLoggingFilter.REQUEST_ID_KEY)).isNull();
        assertThat(MDC.get(UserIdMdcFilter.USER_ID_KEY)).isNull();
    }

    @Test
    void actuator_요청은_남기지_않는다() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(appender.list).isEmpty();
    }

    @Test
    void 회원가입과_로그인_실패_로그에_이메일을_남기지_않는다(CapturedOutput output) throws Exception {
        signup("secret-signup@b.com", DEFAULT_PASSWORD, "비밀");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"secret-login@b.com","password":"password1!"}"""))
                .andExpect(status().is4xxClientError());

        assertThat(output.getOut()).contains("회원가입 성공", "로그인 실패(존재하지 않는 이메일)");
        assertThat(output.getOut()).doesNotContain("secret-signup@b.com", "secret-login@b.com");
    }
}
