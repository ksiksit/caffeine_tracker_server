package com.jongbeom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jongbeom.server.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 사용자(또는 같은 이메일)의 쓰기 요청이 동시에 들어올 때 500 없이 한 번만 반영되는지.
 *
 * <p>요청마다 별도 트랜잭션이어야 경합이 생기므로 베이스의 테스트 트랜잭션(롤백 격리)을 끄고,
 * 만든 계정은 {@link #cleanUp()}에서 지운다(사용자 종속 테이블은 ON DELETE CASCADE).
 * H2 는 잠금·격리 동작이 MySQL 과 완전히 같지 않다 — 경합 자체는 H2 에서도 재현되지만,
 * MySQL REPEATABLE READ 에서만 생기는 문제({@code UserWriteLock} javadoc 의 순서 규칙 위반)는 같은 시나리오를
 * 실제 MySQL 로 돌리는 E2E {@code ConcurrentWriteE2ETest}가 잡는다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentRequestIT extends AbstractIntegrationTest {

    private static final int THREADS = 8;
    private static final String EMAIL_PREFIX = "race-";
    private static final String SETTINGS_BODY = """
            {"halfLife":6.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
            "referenceDoseMg":75,"isLearningEnabled":true,\
            "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true}}""";

    @MockitoBean
    Clock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    @Test
    void 같은_이메일_동시가입은_하나만_201이고_나머지는_409() throws Exception {
        String email = EMAIL_PREFIX + "signup@b.com";
        String body = """
                {"email":"%s","password":"%s","nickname":"경합"}""".formatted(email, DEFAULT_PASSWORD);

        List<Integer> statuses = statusesOf(concurrently(() -> mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()));

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void 가입_직후_설정_동시조회는_전부_200이고_설정행은_하나() throws Exception {
        String accessToken = authToken(EMAIL_PREFIX + "first@b.com", "첫접속");

        List<Integer> statuses = statusesOf(concurrently(() -> mockMvc.perform(get("/api/settings")
                .header("Authorization", "Bearer " + accessToken)).andReturn()));

        assertThat(statuses).containsOnly(200);
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = ?", userId("first@b.com")))
                .isEqualTo(1);
    }

    @Test
    void 같은_수면배치_동시업로드는_전부_200이고_샘플은_한번만_저장() throws Exception {
        String accessToken = authToken(EMAIL_PREFIX + "sleep@b.com", "수면");
        String body = """
                {"samples":[
                  {"clientUuid":"r-bed","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
                  {"clientUuid":"r-core","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T07:30:00+09:00","hkValue":3}
                ]}""";

        List<MvcResult> results = concurrently(() -> mockMvc.perform(post("/api/sleep/samples")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());

        assertThat(statusesOf(results)).containsOnly(200);
        int insertedTotal = 0;
        for (MvcResult result : results) {
            insertedTotal += dataOf(result).get("inserted").asInt();
        }
        assertThat(insertedTotal).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sleep_samples WHERE user_id = ?", userId("sleep@b.com")))
                .isEqualTo(2);
    }

    @Test
    void 학습_동시실행은_전부_200이고_관측은_밤마다_한번만_저장() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-06-02T00:00:00Z"));
        String accessToken = authToken(EMAIL_PREFIX + "learn@b.com", "학습");
        createCaffeineRecord(accessToken, 200, "2026-05-31T19:00:00+09:00");
        uploadSleepSamples(accessToken, """
                [{"clientUuid":"l-bed","start":"2026-05-31T23:00:00+09:00","end":"2026-05-31T23:30:00+09:00","hkValue":0},
                 {"clientUuid":"l-core","start":"2026-05-31T23:30:00+09:00","end":"2026-06-01T07:30:00+09:00","hkValue":3}]""");

        List<MvcResult> results = concurrently(() -> mockMvc.perform(post("/api/learning/run")
                .header("Authorization", "Bearer " + accessToken).param("tz", "Asia/Seoul")).andReturn());

        assertThat(statusesOf(results)).containsOnly(200);
        int updatedTotal = 0;
        for (MvcResult result : results) {
            updatedTotal += dataOf(result).get("updatedCount").asInt();
        }
        assertThat(updatedTotal).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM half_life_observations WHERE user_id = ?", userId("learn@b.com")))
                .isEqualTo(1);
    }

    /**
     * 설정 변경이 진행 중인 같은 사용자의 쓰기(예: 학습 실행)를 기다리는지. 기다리지 않으면 두 트랜잭션이 같은 설정 행을
     * 각자 읽고 덮어써서 한쪽 변경(학습 결과 또는 반감기 변경에 따른 prior 리셋)이 사라진다.
     * 진행 중인 쓰기는 그 사용자의 users 행을 잠근 트랜잭션으로 흉내 낸다.
     */
    @Test
    void 설정_변경은_같은_사용자의_진행중인_쓰기가_끝날때까지_기다린다() throws Exception {
        String accessToken = authToken(EMAIL_PREFIX + "lock@b.com", "잠금");
        long userId = userId("lock@b.com");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> pending = new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
                Future<MvcResult> update = pool.submit(() -> mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY)).andReturn());
                assertThatThrownBy(() -> update.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                return update;
            });

            assertThat(pending.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 같은 요청을 THREADS 개 스레드에서 동시에 시작한다(모든 스레드가 준비된 뒤 한꺼번에 출발). */
    private List<MvcResult> concurrently(Callable<MvcResult> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.call();
                }));
            }
            ready.await();
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statusesOf(List<MvcResult> results) {
        return results.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    private long userId(String emailWithoutPrefix) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, EMAIL_PREFIX + emailWithoutPrefix);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
