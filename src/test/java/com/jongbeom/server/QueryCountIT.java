package com.jongbeom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jongbeom.server.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청당 SQL 실행 횟수 예산. 실제 사용에 가까운 데이터(14일 × 하루 3잔, 7박 수면)로 엔드포인트마다 SQL 수를 세어
 * 상한을 넘으면 실패한다. 특히 학습 실행은 밤 수에 비례해 조회하던 구조(7박 기준 31회, 재실행 16회)를
 * 한 번씩 묶어 읽도록 바꿨으므로, 그 회귀를 막는다. 쓰기(관측 INSERT 는 IDENTITY 라 배치 불가)는 밤 수만큼 남는다.
 *
 * <p>요청끼리 영속성 컨텍스트 캐시가 섞이지 않도록 테스트 트랜잭션을 끈다(요청마다 실제 트랜잭션).
 * 숫자는 DB 와 무관한 JPA 쿼리 수라 H2·MySQL 이 같다. 예산을 올려야 한다면 왜 늘었는지부터 확인한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountIT extends AbstractIntegrationTest {

    private static final String EMAIL = "query-count@b.com";
    private static final String NOW = "2026-06-15T09:00:00+09:00";
    private static final String TZ = "Asia/Seoul";
    private static final String LEARN_FIRST = "POST /api/learning/run (7박 학습)";
    private static final String LEARN_AGAIN = "POST /api/learning/run (재실행)";
    /** 현재 측정값 그대로. 학습: 잠금·설정·수면·학습 날짜·카페인 5회 + 관측 INSERT 7 + 설정 UPDATE 1. */
    private static final Map<String, Long> BUDGETS = budgets();

    private static Map<String, Long> budgets() {
        Map<String, Long> budgets = new LinkedHashMap<>();
        budgets.put("PUT /api/settings", 3L);
        budgets.put("GET /api/settings", 1L);
        budgets.put("GET /api/caffeine/today", 2L);
        budgets.put("GET /api/caffeine-records", 1L);
        budgets.put("GET /api/notifications/plan", 4L);
        budgets.put("GET /api/sleep/summary", 1L);
        budgets.put(LEARN_FIRST, 13L);
        budgets.put(LEARN_AGAIN, 4L);
        budgets.put("GET /api/learning/dashboard", 1L);
        budgets.put("GET /api/insight/intake-stats (14일)", 1L);
        // 인사이트: 기간 전체를 한 번씩 묶어 읽는다(설정·수면·카페인). 밤·날 수에 비례하면 회귀다
        budgets.put("GET /api/insight/caffeine-sleep (14일·7박)", 3L);
        budgets.put("POST /api/sleep/samples (재업로드 6건)", 2L);
        return budgets;
    }

    @MockitoBean
    Clock clock;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);
    }

    @Test
    void 요청당_SQL_수() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-06-15T00:00:00Z"));
        String token = authToken(EMAIL, "측정");
        seed(token);
        String auth = "Bearer " + token;
        Map<String, Long> counts = new LinkedHashMap<>();

        counts.put("PUT /api/settings", count(put("/api/settings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,"referenceDoseMg":75,\
                        "isLearningEnabled":true,"notifications":{"cutoff":true,"bedtimeResidual":true,\
                        "recordReminder":true,"cafeNearby":true}}""")));
        counts.put("GET /api/settings", count(get("/api/settings").header("Authorization", auth)));
        counts.put("GET /api/caffeine/today", count(get("/api/caffeine/today").header("Authorization", auth)
                .param("now", NOW).param("tz", TZ)));
        counts.put("GET /api/caffeine-records", count(get("/api/caffeine-records").header("Authorization", auth)
                .param("now", NOW).param("tz", TZ)));
        counts.put("GET /api/notifications/plan", count(get("/api/notifications/plan").header("Authorization", auth)
                .param("now", NOW).param("tz", TZ)));
        counts.put("GET /api/sleep/summary", count(get("/api/sleep/summary").header("Authorization", auth)
                .param("date", "2026-06-15").param("tz", TZ)));
        counts.put(LEARN_FIRST, count(post("/api/learning/run").header("Authorization", auth)
                .param("tz", TZ)));
        counts.put(LEARN_AGAIN, count(post("/api/learning/run").header("Authorization", auth)
                .param("tz", TZ)));
        counts.put("GET /api/learning/dashboard", count(get("/api/learning/dashboard").header("Authorization", auth)));
        counts.put("GET /api/insight/intake-stats (14일)", count(get("/api/insight/intake-stats")
                .header("Authorization", auth).param("from", "2026-06-02").param("to", "2026-06-15").param("tz", TZ)));
        counts.put("GET /api/insight/caffeine-sleep (14일·7박)", count(get("/api/insight/caffeine-sleep")
                .header("Authorization", auth).param("from", "2026-06-02").param("to", "2026-06-15").param("tz", TZ)));
        counts.put("POST /api/sleep/samples (재업로드 6건)", count(post("/api/sleep/samples").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"samples\":" + nightSamples(LocalDate.parse("2026-06-15")) + "}")));

        // 학습이 실제로 일어났어야 학습 실행 예산이 의미가 있다(아무것도 안 하면 SQL 이 적게 나온다)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM half_life_observations o JOIN users u ON u.id = o.user_id"
                + " WHERE u.email = ?", Integer.class, EMAIL)).isEqualTo(7);

        StringJoiner table = new StringJoiner("\n", "\n", "");
        counts.forEach((k, v) -> table.add("%-40s %d (예산 %d)".formatted(k, v, BUDGETS.get(k))));
        assertThat(counts.keySet()).as("측정 항목과 예산 항목").isEqualTo(BUDGETS.keySet());
        counts.forEach((endpoint, actual) -> assertThat(actual)
                .as("%s SQL 수%s", endpoint, table)
                .isLessThanOrEqualTo(BUDGETS.get(endpoint)));
    }

    private long count(RequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(mockMvc.perform(request).andReturn().getResponse().getStatus()).isLessThan(300);
        return statistics.getPrepareStatementCount();
    }

    /** 2026-06-01~14 하루 3잔(08·13·16시), 2026-06-09~15 아침에 끝나는 7박 수면. */
    private void seed(String token) throws Exception {
        for (LocalDate d = LocalDate.parse("2026-06-01"); d.isBefore(LocalDate.parse("2026-06-15")); d = d.plusDays(1)) {
            for (String hm : new String[] {"08:00", "13:00", "16:00"}) {
                createCaffeineRecord(token, 100, d + "T" + hm + ":00+09:00");
            }
        }
        for (LocalDate morning = LocalDate.parse("2026-06-09");
                !morning.isAfter(LocalDate.parse("2026-06-15")); morning = morning.plusDays(1)) {
            uploadSleepSamples(token, nightSamples(morning));
        }
    }

    /** inBed 23:00~23:20(SOL 20분), 이후 core·deep·rem·core·awake. */
    private static String nightSamples(LocalDate morning) {
        LocalDate eve = morning.minusDays(1);
        String p = "n" + morning;
        return """
                [{"clientUuid":"%1$s-bed","start":"%2$sT23:00:00+09:00","end":"%2$sT23:20:00+09:00","hkValue":0},
                 {"clientUuid":"%1$s-c1","start":"%2$sT23:20:00+09:00","end":"%3$sT03:00:00+09:00","hkValue":3},
                 {"clientUuid":"%1$s-d","start":"%3$sT03:00:00+09:00","end":"%3$sT04:00:00+09:00","hkValue":4},
                 {"clientUuid":"%1$s-r","start":"%3$sT04:00:00+09:00","end":"%3$sT05:00:00+09:00","hkValue":5},
                 {"clientUuid":"%1$s-c2","start":"%3$sT05:00:00+09:00","end":"%3$sT07:00:00+09:00","hkValue":3},
                 {"clientUuid":"%1$s-aw","start":"%3$sT07:00:00+09:00","end":"%3$sT07:05:00+09:00","hkValue":2}]"""
                .formatted(p, eve, morning);
    }
}
