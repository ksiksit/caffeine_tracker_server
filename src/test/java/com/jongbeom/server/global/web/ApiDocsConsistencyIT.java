package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jongbeom.server.domain.calc.Pharmacokinetics;
import com.jongbeom.server.domain.learning.LearningSkipReason;
import com.jongbeom.server.global.error.ErrorCode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * API 문서 정합성 — 서버 코드와 docs/api.md, docs/openapi.yaml 이 어긋나면 빌드가 실패한다.
 * <ul>
 *   <li>엔드포인트 목록: 핸들러 매핑 == api.md 목록 표 == openapi.yaml paths</li>
 *   <li>DTO 필드: domain/*&#47;dto 와 global/error 의 record 필드 == openapi.yaml components.schemas 의 properties</li>
 *   <li>enum 목록: ErrorCode, LearningSkipReason, CutoffResult.Status == openapi.yaml 의 enum</li>
 * </ul>
 * 설명·제약 문구는 검사하지 않는다(사람이 맞춘다). 문서 파일은 build.gradle 의 processTestResources 가 docs/ 로 복사한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiDocsConsistencyIT {

    /** api.md "엔드포인트 목록" 표의 행: | 화면 | 기능 | `METHOD` | `/path` | 인증 | */
    private static final Pattern MD_ENDPOINT_ROW = Pattern.compile(
            "^\\|[^|]*\\|[^|]*\\|\\s*`(GET|POST|PUT|DELETE|PATCH)`\\s*\\|\\s*`(/[^`]*)`\\s*\\|");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");
    private static final String BASE_PACKAGE = "com.jongbeom.server";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void 엔드포인트_목록은_코드_apimd_openapi_세곳이_같다() throws Exception {
        Set<String> code = codeEndpoints();
        assertThat(code).isNotEmpty();
        assertThat(markdownEndpoints()).as("docs/api.md 엔드포인트 목록 표").containsExactlyInAnyOrderElementsOf(code);
        assertThat(openapiEndpoints()).as("docs/openapi.yaml paths").containsExactlyInAnyOrderElementsOf(code);
    }

    @Test
    void DTO_레코드_필드는_openapi_스키마와_같다() throws Exception {
        Map<String, Map<String, Object>> schemas = schemas();
        List<Class<?>> records = documentedRecords();
        assertThat(records).isNotEmpty();
        for (Class<?> record : records) {
            String name = record.getSimpleName();
            assertThat(schemas).as("openapi.yaml components.schemas 에 %s 누락", name).containsKey(name);
            Set<String> expected = Arrays.stream(record.getRecordComponents())
                    .map(component -> component.getName())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(properties(schemas.get(name)).keySet()).as("%s 필드", name)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void 에러코드와_enum_목록은_openapi와_같다() throws Exception {
        Map<String, Map<String, Object>> schemas = schemas();
        assertThat(enumOf(schemas, "ApiError", "code"))
                .containsExactlyInAnyOrderElementsOf(names(ErrorCode.values()));
        assertThat(enumOf(schemas, "LearningRunResponse", "skipReason"))
                .containsExactlyInAnyOrderElementsOf(names(LearningSkipReason.values()));
        assertThat(enumOf(schemas, "CutoffResponse", "status"))
                .containsExactlyInAnyOrderElementsOf(names(Pharmacokinetics.CutoffResult.Status.values()));
    }

    /** 컨트롤러에 등록된 "METHOD /api/..." 집합. /error 등 메서드 미지정 매핑은 제외. */
    private Set<String> codeEndpoints() {
        Set<String> result = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (info.getPathPatternsCondition() == null) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                info.getMethodsCondition().getMethods()
                        .forEach(httpMethod -> result.add(httpMethod.name() + " " + pattern));
            }
        });
        return result;
    }

    private static Set<String> markdownEndpoints() throws Exception {
        Set<String> result = new TreeSet<>();
        for (String line : read("docs/api.md").split("\n")) {
            Matcher matcher = MD_ENDPOINT_ROW.matcher(line);
            if (matcher.find()) {
                result.add(matcher.group(1) + " " + matcher.group(2));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> openapiEndpoints() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) loadYaml().get("paths");
        Set<String> result = new TreeSet<>();
        paths.forEach((path, item) -> ((Map<String, Object>) item).keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(httpMethod -> result.add(httpMethod.toUpperCase() + " " + path)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> schemas() throws Exception {
        Map<String, Object> components = (Map<String, Object>) loadYaml().get("components");
        return (Map<String, Map<String, Object>>) components.get("schemas");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        Object props = schema.get("properties");
        assertThat(props).as("스키마에 properties 없음: %s", schema).isNotNull();
        return (Map<String, Object>) props;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(Map<String, Map<String, Object>> schemas, String schema, String property) {
        Map<String, Object> prop = (Map<String, Object>) properties(schemas.get(schema)).get(property);
        return (List<String>) prop.get("enum");
    }

    private static Map<String, Object> loadYaml() throws Exception {
        try (InputStream in = new ClassPathResource("docs/openapi.yaml").getInputStream()) {
            return new Yaml().load(in);
        }
    }

    private static String read(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /** domain/*&#47;dto 와 global/error 의 record 전부(중첩 record 포함). */
    private static List<Class<?>> documentedRecords() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> cls = Class.forName(definition.getBeanClassName());
            String pkg = cls.getPackageName();
            if (cls.isRecord() && (pkg.endsWith(".dto") || pkg.equals(BASE_PACKAGE + ".global.error"))) {
                result.add(cls);
            }
        }
        return result;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
