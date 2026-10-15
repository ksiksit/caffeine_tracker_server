package com.jongbeom.server.e2e;

import java.io.File;
import java.time.Duration;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * E2E 대상 서버를 정한다. 두 모드:
 * <ul>
 *   <li><b>local</b>(기본): docker-compose.e2e.yml 을 Testcontainers 로 띄운다 — 현재 소스로 빌드한 이미지 + 빈 MySQL.</li>
 *   <li><b>external</b>: {@code -Pe2e.baseUrl=...} 이 있으면 컨테이너 없이 그 서버를 쓴다(배포 후 스모크).</li>
 * </ul>
 * 환경은 JVM 당 한 번만 띄워 모든 테스트 클래스가 공유한다(싱글턴 컨테이너 패턴 — 클래스마다 띄우면 이미지 빌드·기동이 반복된다).
 */
public final class E2eEnvironment {

    private static final String APP_SERVICE = "app";
    private static final int APP_PORT = 8080;
    /** 첫 실행은 이미지 빌드(의존성 다운로드 포함)까지 하므로 넉넉히. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

    private static final String BASE_URL = resolveBaseUrl();

    private E2eEnvironment() {
    }

    public static String baseUrl() {
        return BASE_URL;
    }

    /** local 모드면 매 실행이 빈 DB 라 데이터를 마음껏 써도 된다. external(운영)에서는 쓰기 시나리오를 건너뛴다. */
    public static boolean isLocal() {
        return externalBaseUrl() == null;
    }

    private static String externalBaseUrl() {
        String url = System.getProperty("e2e.baseUrl");
        return (url == null || url.isBlank()) ? null : url;
    }

    private static String resolveBaseUrl() {
        String external = externalBaseUrl();
        if (external != null) {
            return external;
        }
        File composeFile = new File(System.getProperty("e2e.projectDir"), "docker-compose.e2e.yml");
        ComposeContainer environment = new ComposeContainer(composeFile)
                .withLocalCompose(true)   // 로컬 docker compose CLI 사용(빌드 컨텍스트를 그대로 쓰기 위해)
                .withBuild(true)          // 매번 현재 소스로 이미지를 다시 빌드 — 레이어 캐시로 변경분만
                .withExposedService(APP_SERVICE, APP_PORT,
                        Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(STARTUP_TIMEOUT));
        environment.start();
        // JVM 종료 시 compose down. (Testcontainers 의 Ryuk 도 라벨로 정리하지만 네트워크까지 확실히 내린다)
        Runtime.getRuntime().addShutdownHook(new Thread(environment::stop));
        return "http://" + environment.getServiceHost(APP_SERVICE, APP_PORT)
                + ":" + environment.getServicePort(APP_SERVICE, APP_PORT);
    }
}
