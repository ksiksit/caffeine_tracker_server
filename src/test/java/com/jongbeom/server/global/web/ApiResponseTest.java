package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jongbeom.server.global.error.ApiError;
import com.jongbeom.server.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 봉투 직렬화 계약: 키 4개는 null 이어도 항상 존재, error.fieldErrors 는 있을 때만. */
class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode serialize(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    @Test
    void 성공_봉투는_키4개를_모두_갖고_error_message는_null이다() throws Exception {
        JsonNode node = serialize(ApiResponse.ok(new Payload("v")));
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("success", "data", "error", "message");
        assertThat(node.get("success").asBoolean()).isTrue();
        assertThat(node.get("data").get("value").asText()).isEqualTo("v");
        assertThat(node.get("error").isNull()).isTrue();
        assertThat(node.get("message").isNull()).isTrue();
    }

    @Test
    void 바디없는_성공은_data가_null이다() throws Exception {
        JsonNode node = serialize(ApiResponse.ok());
        assertThat(node.get("success").asBoolean()).isTrue();
        assertThat(node.get("data").isNull()).isTrue();
    }

    @Test
    void 실패_봉투는_error_code와_message를_채우고_fieldErrors키는_생략한다() throws Exception {
        JsonNode node = serialize(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
        assertThat(node.get("success").asBoolean()).isFalse();
        assertThat(node.get("data").isNull()).isTrue();
        assertThat(node.get("error").get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(node.get("error").has("fieldErrors")).isFalse();
        assertThat(node.get("message").asText()).isEqualTo(ErrorCode.INTERNAL_ERROR.message());
    }

    @Test
    void 검증실패_봉투는_fieldErrors를_포함한다() throws Exception {
        JsonNode node = serialize(ApiResponse.error(
                ErrorCode.VALIDATION_FAILED, List.of(new ApiError.FieldError("email", "must not be blank"))));
        assertThat(node.get("error").get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(node.get("error").get("fieldErrors").get(0).get("field").asText()).isEqualTo("email");
    }

    record Payload(String value) {
    }
}
