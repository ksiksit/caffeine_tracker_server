package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** application-prod.yaml 의 관리 포트 분리 — 헬스·메트릭은 관리 포트에서만 응답하고 공개 포트에는 없어야 한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0") // 8081 고정 대신 빈 포트
@AutoConfigureObservability(tracing = false) // 테스트 기본값은 메트릭 내보내기를 끈다
@ActiveProfiles({"test", "prod"})
class ActuatorPortIT {

    @LocalServerPort
    private int serverPort;
    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void 관리포트의_헬스는_인증없이_200() {
        ResponseEntity<String> response = get(managementPort, "/actuator/health");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void 관리포트의_메트릭은_인증없이_200이고_JVM과_Hikari를_포함() {
        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes", "hikaricp_connections");
    }

    @Test
    void 공개포트에는_헬스와_메트릭이_없다() {
        assertThat(get(serverPort, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(serverPort, "/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 공개포트의_API는_그대로_인증을_요구한다() {
        assertThat(get(serverPort, "/api/settings").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 관리포트의_다른_actuator_경로는_노출되지_않는다() {
        assertThat(get(managementPort, "/actuator/env").getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> get(int port, String path) {
        return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
    }
}
