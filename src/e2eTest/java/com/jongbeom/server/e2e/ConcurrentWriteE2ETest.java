package com.jongbeom.server.e2e;

import static com.jongbeom.server.e2e.E2eApi.KST;
import static com.jongbeom.server.e2e.E2eApi.TZ;
import static com.jongbeom.server.e2e.E2eApi.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

/**
 * 같은 사용자의 쓰기 요청이 실제 MySQL 에 동시에 들어올 때 500 없이 한 번만 반영되는지.
 *
 * <p>통합 테스트 {@code ConcurrentRequestIT}와 시나리오는 같지만 H2(READ COMMITTED)가 아니라 MySQL(InnoDB) 기본 격리 수준
 * REPEATABLE READ 에서 돈다. 사용자 쓰기 잠금({@code UserWriteLock.lock})은 트랜잭션의 첫 문장이어야 한다 — 잠금보다
 * 먼저 일반 SELECT 를 하면 그 시점에 스냅숏이 고정돼, 잠금을 기다리는 동안 앞 요청이 커밋한 관측·샘플이 보이지 않는다.
 * 그러면 같은 밤·같은 client_uuid 를 다시 저장하려다 UNIQUE 위반으로 500 이 난다. H2 에서는 순서를 어겨도 통과하므로
 * 이 순서 규칙을 잡는 테스트는 여기뿐이다.
 *
 * <p>경합은 비결정적이라 라운드마다 새 사용자로 반복한다. 데이터를 쓰므로 local 모드에서만 돈다.
 */
class ConcurrentWriteE2ETest {

    /** 동시 요청 수. 앱 커넥션 풀(prod 기본 10)보다 작게 둔다 — 넘으면 일부는 풀에서 줄을 서서 DB 에서 겹치지 않는다. */
    private static final int THREADS = 8;
    /**
     * 라운드 수. 잠금 순서를 어기면 대부분의 라운드가 깨지지만(로컬 측정: 학습은 15라운드 전부, 수면 업로드는 5라운드 중 4)
     * 비결정적이므로 여러 번 돈다.
     */
    private static final int ROUNDS = 5;
    /** 학습할 밤 수. 밤마다 관측 INSERT·설정 UPDATE 가 붙어 먼저 잠금을 잡은 요청의 트랜잭션이 길어진다 = 경합 창이 넓어진다. */
    private static final int NIGHTS = 3;
    private static final String LOCK_ORDER_HINT =
            "UserWriteLock.lock 이 트랜잭션 첫 문장인지 확인 — 잠금 전에 읽으면 앞 요청의 커밋이 안 보여 UNIQUE 위반(500)";

    @BeforeAll
    static void localOnly() {
        assumeTrue(E2eEnvironment.isLocal(), "쓰기 시나리오는 local(빈 DB) 모드에서만 실행한다");
    }

    @RepeatedTest(value = ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    void 학습_동시실행은_전부_200이고_관측은_밤마다_한번만_저장() throws Exception {
        String access = signupAndLogin("race-learn");
        // 서버는 진짜 시계로 "오늘"을 정한다 — 최근 7일 학습 후보 안에 들도록 오늘 아침까지의 밤 NIGHTS 개를 만든다
        LocalDate today = LocalDate.now(KST);
        for (int i = 0; i < NIGHTS; i++) {
            LocalDate morning = today.minusDays(i);
            LocalDate evening = morning.minusDays(1);
            createRecord(access, 200, evening + "T19:00:00+09:00");
            api(access).body(sleepBatch("n" + i, evening, morning))
                    .when().post("/api/sleep/samples")
                    .then().statusCode(200).body("data.inserted", equalTo(2));
        }

        List<Response> responses = postConcurrently(
                () -> api(access).queryParam("tz", TZ), "/api/learning/run");

        assertThat(statusesOf(responses)).as(LOCK_ORDER_HINT).containsOnly(200);
        // 먼저 잠금을 잡은 한 요청이 밤을 전부 학습하고, 나머지는 그 커밋을 보고 ALREADY_LEARNED 로 건너뛴다
        assertThat(sumOf(responses, "data.updatedCount")).isEqualTo(NIGHTS);
        api(access).when().get("/api/learning/dashboard")
                .then().statusCode(200).body("data.count", equalTo(NIGHTS));
        api(access).when().get("/api/settings")
                .then().statusCode(200).body("data.lastLearnedDate", equalTo(today.toString()));
    }

    @RepeatedTest(value = ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    void 같은_수면배치_동시업로드는_전부_200이고_샘플은_한번만_저장() throws Exception {
        String access = signupAndLogin("race-sleep");
        LocalDate today = LocalDate.now(KST);
        String batch = sleepBatch("s", today.minusDays(1), today);

        List<Response> responses = postConcurrently(() -> api(access).body(batch), "/api/sleep/samples");

        assertThat(statusesOf(responses)).as(LOCK_ORDER_HINT).containsOnly(200);
        assertThat(sumOf(responses, "data.inserted")).isEqualTo(2);
        api(access).body(batch).when().post("/api/sleep/samples")
                .then().statusCode(200).body("data.inserted", equalTo(0));
    }

    /**
     * 같은 요청을 THREADS 개 스레드에서 동시에 POST 한다({@code ConcurrentRequestIT}와 같은 출발 신호 방식).
     * 요청 준비는 출발 전에 끝내 두어 스레드 간 출발 시차를 줄인다.
     */
    private static List<Response> postConcurrently(Supplier<RequestSpecification> request, String path)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    RequestSpecification prepared = request.get();
                    ready.countDown();
                    start.await();
                    return prepared.when().post(path);
                }));
            }
            ready.await();
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statusesOf(List<Response> responses) {
        return responses.stream().map(Response::statusCode).toList();
    }

    private static int sumOf(List<Response> responses, String path) {
        return responses.stream().mapToInt(r -> r.jsonPath().getInt(path)).sum();
    }

    /** evening 23:00 취침 · 23:30 잠듦 · morning 07:30 기상. client_uuid 는 사용자 안에서만 유일하면 된다. */
    private static String sleepBatch(String uuidPrefix, LocalDate evening, LocalDate morning) {
        return """
                {"samples":[
                  {"clientUuid":"%1$s-bed","start":"%2$sT23:00:00+09:00","end":"%2$sT23:30:00+09:00","hkValue":0},
                  {"clientUuid":"%1$s-core","start":"%2$sT23:30:00+09:00","end":"%3$sT07:30:00+09:00","hkValue":3}]}"""
                .formatted(uuidPrefix, evening, morning);
    }

    private static String signupAndLogin(String prefix) {
        String email = E2eApi.uniqueEmail(prefix);
        E2eApi.signup(email, "경합");
        return E2eApi.login(email, E2eApi.PASSWORD).getString("accessToken");
    }

    private static void createRecord(String access, int amount, String timestamp) {
        api(access).body("""
                        {"amount":%d,"drinkName":"커피","timestamp":"%s"}""".formatted(amount, timestamp))
                .when().post("/api/caffeine-records")
                .then().statusCode(201);
    }
}
