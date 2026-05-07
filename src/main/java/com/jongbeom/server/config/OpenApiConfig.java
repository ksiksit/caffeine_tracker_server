package com.jongbeom.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";
    private static final String ERROR_RESPONSE_REF = "#/components/schemas/ErrorResponse";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Caffeine Tracker API")
                        .version("0.0.1")
                        .description(apiDescription()))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Access Token")));
    }

    @Bean
    public OperationCustomizer commonErrorResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            responses.computeIfAbsent("400",
                    k -> errorApiResponse("잘못된 요청 형식 또는 입력값 검증 실패 (code: MALFORMED_JSON, VALIDATION_FAILED)"));
            responses.computeIfAbsent("500",
                    k -> errorApiResponse("서버 내부 오류 (code: INTERNAL_ERROR)"));
            return operation;
        };
    }

    private ApiResponse errorApiResponse(String description) {
        Schema<?> ref = new Schema<>().$ref(ERROR_RESPONSE_REF);
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(ref)));
    }

    private String apiDescription() {
        return """
                ## 개요
                카페인 섭취량 추적 서비스 백엔드 API. 인증·사용자 관리 엔드포인트를 제공합니다.

                ## JWT 인증 흐름 (iOS)
                1. `POST /api/auth/signup` 으로 회원가입
                2. `POST /api/auth/login` 으로 로그인 → `accessToken`, `refreshToken` 수신
                3. 인증이 필요한 모든 요청 헤더에 `Authorization: Bearer {accessToken}` 부착
                4. 401 응답 시 `POST /api/auth/refresh` 로 토큰 재발급 후 원 요청 재시도
                5. `POST /api/auth/logout` 으로 서버측 RefreshToken 폐기 + 클라이언트 저장소 삭제

                ## 토큰 만료
                - **Access Token**: 3600초 (1시간)
                - **Refresh Token**: 1209600초 (14일)

                Refresh 호출 시 새 accessToken/refreshToken이 함께 반환됩니다 (로테이션). 두 토큰 모두 갱신해 저장하세요.

                ## iOS 구현 가이드
                - **토큰 저장**: Keychain 사용 권장 (UserDefaults는 평문 저장이라 부적합)
                - **자동 갱신**: URLSession 인터셉터(또는 URLProtocol)로 401 응답을 가로채 refresh 후 재시도하는 패턴 권장
                - **Authorize 모달 사용법**: 우상단 Authorize 버튼 클릭 후 입력란에 **토큰 값만** 입력 (`Bearer ` 접두사는 자동 부여)

                ## 에러 응답 포맷
                모든 에러는 다음 구조를 따릅니다:
                ```
                {
                  "code": "EMAIL_ALREADY_EXISTS",
                  "message": "이미 사용 중인 이메일입니다.",
                  "fieldErrors": null,
                  "timestamp": "2026-05-02T10:30:00+00:00"
                }
                ```
                필드 검증 실패(`VALIDATION_FAILED`) 시 `fieldErrors` 배열에 필드별 오류가 포함됩니다.

                ## OpenAPI 스펙 다운로드
                `/v3/api-docs` (JSON) 또는 `/v3/api-docs.yaml` 로 OpenAPI 스펙을 받아 [openapi-generator](https://openapi-generator.tech/) 로 Swift 모델/클라이언트 코드를 자동 생성할 수 있습니다.
                """;
    }
}
