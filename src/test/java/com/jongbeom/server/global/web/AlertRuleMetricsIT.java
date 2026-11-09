package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

/**
 * 경보 규칙(monitoring/prometheus/alerts.yml)이 쓰는 메트릭·라벨 이름이 /actuator/prometheus 에 실제로 나오는지 대조한다.
 *
 * <p>메트릭 이름이나 태그 키가 바뀌어도 경보 식은 문법상 그대로 유효해서 promtool 검사를 통과하고, 경보만 조용히 울리지 않게 된다.
 * 그 회귀를 빌드에서 잡는다. 임계값·경계는 monitoring/prometheus/alerts.test.yml(promtool, monitoring/check.sh)이 따로 검사한다.
 * 규칙 파일은 build.gradle 의 processTestResources 가 클래스패스의 같은 경로로 복사한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0") // 8081 고정 대신 빈 포트
@AutoConfigureObservability(tracing = false) // 테스트 기본값은 메트릭 내보내기를 끈다
@ActiveProfiles({"test", "prod"})
class AlertRuleMetricsIT {

    private static final String ALERTS = "monitoring/prometheus/alerts.yml";

    /** 메트릭 선택자 = 이름 바로 뒤의 중괄호. alerts.yml 은 모든 선택자에 중괄호를 붙인다(파일 머리 주석). */
    private static final Pattern SELECTOR = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)}");
    /** 선택자 안의 라벨 이름 — job="..", uri!~"..", status=~".." 의 왼쪽. */
    private static final Pattern MATCHER_LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=~|!~|!=|=)\"");
    /** 노출 형식 한 줄의 라벨 이름 — name{a="1",b="2"} 의 a, b. */
    private static final Pattern EXPOSED_LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"");

    /** Prometheus 가 수집하면서 만들거나 붙이는 것 — 앱이 내보내지 않는다. */
    private static final Set<String> SCRAPE_METRICS = Set.of("up");
    private static final Set<String> SCRAPE_LABELS = Set.of("job", "instance");

    @LocalServerPort
    private int serverPort;
    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void 경보_규칙이_쓰는_메트릭과_라벨이_모두_노출된다() throws IOException {
        // HTTP 요청 메트릭(http_server_requests_*)은 요청이 하나는 있어야 생긴다. 401 이어도 기록된다
        restTemplate.getForEntity(url(serverPort, "/api/settings"), String.class);
        Map<String, Set<String>> exposed =
                exposedMetrics(restTemplate.getForObject(url(managementPort, "/actuator/prometheus"), String.class));
        Map<String, Set<String>> used = metricsUsedByAlerts();

        assertThat(used).as("alerts.yml 에서 메트릭 선택자를 하나도 찾지 못했다 — 파일 경로나 파서 문제").isNotEmpty();
        for (Map.Entry<String, Set<String>> entry : used.entrySet()) {
            String metric = entry.getKey();
            if (SCRAPE_METRICS.contains(metric)) {
                continue;
            }
            assertThat(exposed)
                    .as("alerts.yml 이 쓰는 메트릭 %s 가 /actuator/prometheus 에 없다 — 경보가 조용히 울리지 않게 된다", metric)
                    .containsKey(metric);
            Set<String> appLabels = new TreeSet<>(entry.getValue());
            appLabels.removeAll(SCRAPE_LABELS);
            assertThat(exposed.get(metric))
                    .as("메트릭 %s 에 alerts.yml 이 거르는 라벨이 없다", metric)
                    .containsAll(appLabels);
        }
    }

    @Test
    void 파서가_규칙의_주요_메트릭을_찾는다() throws IOException {
        // 파서가 아무것도 못 찾으면 위 테스트가 빈 집합으로 통과할 수 있어, 알려진 이름 몇 개로 파서 자체를 확인한다
        assertThat(metricsUsedByAlerts())
                .containsKeys("up", "http_server_requests_seconds_count", "hikaricp_connections_pending",
                        "caffeine_auth_logins_total", "disk_free_bytes")
                .doesNotContainKey("vector");
        assertThat(metricsUsedByAlerts().get("http_server_requests_seconds_count")).contains("job", "uri", "status");
    }

    /** 규칙마다 expr 에서 메트릭 이름 → 선택자에 쓰인 라벨 이름. 문구(annotations)는 보지 않는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> metricsUsedByAlerts() throws IOException {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(ALERTS).getInputStream()) {
            root = new Yaml().load(in);
        }
        Map<String, Set<String>> used = new TreeMap<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                Matcher selector = SELECTOR.matcher(String.valueOf(rule.get("expr")));
                while (selector.find()) {
                    Set<String> labels = used.computeIfAbsent(selector.group(1), k -> new TreeSet<>());
                    Matcher label = MATCHER_LABEL.matcher(selector.group(2));
                    while (label.find()) {
                        labels.add(label.group(1));
                    }
                }
            }
        }
        return used;
    }

    /** Prometheus 텍스트 형식에서 메트릭 이름 → 그 이름의 시계열에 붙은 라벨 이름 전체. */
    private static Map<String, Set<String>> exposedMetrics(String body) {
        Map<String, Set<String>> exposed = new HashMap<>();
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int brace = line.indexOf('{');
            String name = line.substring(0, brace >= 0 ? brace : line.indexOf(' '));
            Set<String> labels = exposed.computeIfAbsent(name, k -> new HashSet<>());
            if (brace >= 0) {
                Matcher label = EXPOSED_LABEL.matcher(line.substring(brace, line.lastIndexOf('}') + 1));
                while (label.find()) {
                    labels.add(label.group(1));
                }
            }
        }
        return exposed;
    }

    private static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }
}
