package com.jongbeom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.domain.auth.LoginMetrics;
import com.jongbeom.server.domain.learning.LearningMetrics;
import com.jongbeom.server.domain.learning.LearningSkipReason;
import com.jongbeom.server.domain.sleep.SleepMetrics;
import com.jongbeom.server.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 도메인 메트릭(학습·수면 업로드·로그인)이 실제 요청 경로에서 기대한 만큼 오르는지.
 * 레지스트리는 컨텍스트 단위로 재사용되어 다른 테스트의 값이 남아 있으므로, 절대값이 아니라 요청 전후 증가분을 본다.
 */
class DomainMetricsIT extends AbstractIntegrationTest {

    private static final String LEARNED = LearningMetrics.LEARNED;

    @MockitoBean
    Clock clock;
    @Autowired
    MeterRegistry registry;

    /** 메트릭 이름 → (태그 값 → 테스트 시작 시 카운트). */
    private final Map<String, Map<String, Double>> baseline = new HashMap<>();

    @BeforeEach
    void stubTodayAndSnapshot() {
        // today = 2026-06-02 KST → 직전 7일이 학습 후보
        given(clock.instant()).willReturn(Instant.parse("2026-06-02T00:00:00Z"));
        snapshotAll();
    }

    @Test
    void 가능한_outcome은_요청이_없어도_0부터_등록돼_있다() {
        Set<String> skipReasons = Arrays.stream(LearningSkipReason.values())
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(tagValues(LearningMetrics.RUNS))
                .containsAll(skipReasons).contains(LEARNED).hasSize(skipReasons.size() + 1);
        // 실행 전체가 멈추는 사유는 밤 단위 카운터에 없다
        assertThat(tagValues(LearningMetrics.NIGHTS))
                .contains(LEARNED, "ALREADY_LEARNED", "SLEEP_TOO_SHORT", "INSUFFICIENT_CAFFEINE_RESIDUAL")
                .doesNotContain("LEARNING_DISABLED", "NO_SLEEP_SUMMARY")
                .hasSize(skipReasons.size() - 2 + 1);
        assertThat(tagValues(SleepMetrics.SAMPLES))
                .containsExactlyInAnyOrder(SleepMetrics.INSERTED, SleepMetrics.DUPLICATE);
        assertThat(tagValues(LoginMetrics.LOGINS))
                .containsExactlyInAnyOrder("SUCCESS", "WRONG_PASSWORD", "UNKNOWN_EMAIL");
    }

    @Test
    void 학습_실행은_실행_결과와_밤별_결과를_따로_센다() throws Exception {
        String accessToken = authToken("metrics@b.com", "메트릭");
        // 05-31 아침: 학습 성공 / 06-01 아침(더 최신): 수면 3h → SLEEP_TOO_SHORT
        caffeine(accessToken, 200, "2026-05-30T19:00:00");
        sleepNight(accessToken, "a", "2026-05-30", "2026-05-31T07:30:00");
        sleepNight(accessToken, "b", "2026-05-31", "2026-06-01T02:30:00");

        runLearning(accessToken);

        assertThat(delta(LearningMetrics.RUNS, LEARNED)).isEqualTo(1);
        assertThat(delta(LearningMetrics.NIGHTS, LEARNED)).isEqualTo(1);
        assertThat(delta(LearningMetrics.NIGHTS, "SLEEP_TOO_SHORT")).isEqualTo(1);

        // 재실행: 응답은 최신 밤 사유(SLEEP_TOO_SHORT) 하나뿐이지만, 밤별 카운터에는 이미 학습된 밤도 남는다
        runLearning(accessToken);

        assertThat(delta(LearningMetrics.RUNS, LEARNED)).isEqualTo(1);
        assertThat(delta(LearningMetrics.RUNS, "SLEEP_TOO_SHORT")).isEqualTo(1);
        assertThat(delta(LearningMetrics.NIGHTS, "ALREADY_LEARNED")).isEqualTo(1);
        assertThat(delta(LearningMetrics.NIGHTS, "SLEEP_TOO_SHORT")).isEqualTo(2);
        assertThat(totalDelta(LearningMetrics.RUNS)).isEqualTo(2);
        assertThat(totalDelta(LearningMetrics.NIGHTS)).isEqualTo(4);
    }

    @Test
    void 학습이_꺼져_있거나_수면이_없으면_실행만_세고_밤은_세지_않는다() throws Exception {
        String accessToken = authToken("metrics@b.com", "메트릭");
        runLearning(accessToken); // 수면 데이터 없음

        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                                "referenceDoseMg":75,"isLearningEnabled":false,\
                                "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}"""))
                .andExpect(status().isOk());
        runLearning(accessToken);

        assertThat(delta(LearningMetrics.RUNS, "NO_SLEEP_SUMMARY")).isEqualTo(1);
        assertThat(delta(LearningMetrics.RUNS, "LEARNING_DISABLED")).isEqualTo(1);
        assertThat(totalDelta(LearningMetrics.NIGHTS)).isZero();
    }

    @Test
    void 수면_업로드는_새로_저장한_수와_중복으로_버린_수를_센다() throws Exception {
        String accessToken = authToken("metrics@b.com", "메트릭");
        String s1 = sample("s1", "2026-05-31T23:00:00", "2026-05-31T23:30:00", 0);
        String s2 = sample("s2", "2026-05-31T23:30:00", "2026-06-01T07:00:00", 3);
        String s3 = sample("s3", "2026-06-01T07:00:00", "2026-06-01T07:10:00", 2);

        uploadSleepSamples(accessToken, "[" + s1 + "," + s2 + "]");
        assertThat(delta(SleepMetrics.SAMPLES, SleepMetrics.INSERTED)).isEqualTo(2);
        assertThat(delta(SleepMetrics.SAMPLES, SleepMetrics.DUPLICATE)).isZero();

        // 이미 저장된 2개 + 새 1개 + 같은 배치 안의 중복 1개 → 저장 1, 중복 3
        uploadSleepSamples(accessToken, "[" + s1 + "," + s2 + "," + s3 + "," + s3 + "]");
        assertThat(delta(SleepMetrics.SAMPLES, SleepMetrics.INSERTED)).isEqualTo(3);
        assertThat(delta(SleepMetrics.SAMPLES, SleepMetrics.DUPLICATE)).isEqualTo(3);
    }

    @Test
    void 로그인은_성공과_실패_사유를_나눠_세고_시도제한에_막힌_요청은_세지_않는다() throws Exception {
        signup("a@b.com", DEFAULT_PASSWORD, "로그인");
        login("a@b.com", DEFAULT_PASSWORD);
        loginExpecting("nobody@b.com", DEFAULT_PASSWORD, 401);
        for (int i = 0; i < 5; i++) {
            loginExpecting("a@b.com", "wrong-password", 401);
        }
        loginExpecting("a@b.com", DEFAULT_PASSWORD, 429); // 한도 초과 — 검증 전에 막힌다

        assertThat(delta(LoginMetrics.LOGINS, "SUCCESS")).isEqualTo(1);
        assertThat(delta(LoginMetrics.LOGINS, "UNKNOWN_EMAIL")).isEqualTo(1);
        assertThat(delta(LoginMetrics.LOGINS, "WRONG_PASSWORD")).isEqualTo(5);
        assertThat(totalDelta(LoginMetrics.LOGINS)).isEqualTo(7);
    }

    // ---- 요청 헬퍼 ----

    private void runLearning(String accessToken) throws Exception {
        mockMvc.perform(post("/api/learning/run").header("Authorization", "Bearer " + accessToken)
                        .param("tz", "Asia/Seoul"))
                .andExpect(status().isOk());
    }

    private void caffeine(String accessToken, int mg, String tsKst) throws Exception {
        createCaffeineRecord(accessToken, mg, tsKst + "+09:00");
    }

    /** inBed 23:00~23:30, core 23:30~{@code coreEndKst} (SOL 30분). */
    private void sleepNight(String accessToken, String prefix, String inBedDateKst, String coreEndKst)
            throws Exception {
        uploadSleepSamples(accessToken, "[" + sample(prefix + "-bed", inBedDateKst + "T23:00:00",
                inBedDateKst + "T23:30:00", 0) + "," + sample(prefix + "-core", inBedDateKst + "T23:30:00",
                coreEndKst, 3) + "]");
    }

    private static String sample(String uuid, String startKst, String endKst, int hkValue) {
        return """
                {"clientUuid":"%s","start":"%s+09:00","end":"%s+09:00","hkValue":%d}"""
                .formatted(uuid, startKst, endKst, hkValue);
    }

    private void loginExpecting(String email, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andExpect(status().is(expectedStatus));
    }

    // ---- 메트릭 헬퍼 ----

    private static final Map<String, String> TAG_KEYS = Map.of(
            LearningMetrics.RUNS, LearningMetrics.OUTCOME_TAG,
            LearningMetrics.NIGHTS, LearningMetrics.OUTCOME_TAG,
            SleepMetrics.SAMPLES, SleepMetrics.RESULT_TAG,
            LoginMetrics.LOGINS, LoginMetrics.OUTCOME_TAG);

    private void snapshotAll() {
        TAG_KEYS.forEach((name, tagKey) -> baseline.put(name, registry.find(name).counters().stream()
                .collect(Collectors.toMap(c -> c.getId().getTag(tagKey), Counter::count))));
    }

    private Set<String> tagValues(String name) {
        return baseline.get(name).keySet();
    }

    private double delta(String name, String tagValue) {
        double now = registry.get(name).tag(TAG_KEYS.get(name), tagValue).counter().count();
        return now - baseline.get(name).get(tagValue);
    }

    private double totalDelta(String name) {
        double now = registry.get(name).counters().stream().mapToDouble(Counter::count).sum();
        double before = baseline.get(name).values().stream().mapToDouble(Double::doubleValue).sum();
        return now - before;
    }
}
