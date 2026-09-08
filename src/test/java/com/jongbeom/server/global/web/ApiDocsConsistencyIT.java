package com.jongbeom.server.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jongbeom.server.domain.calc.Pharmacokinetics;
import com.jongbeom.server.domain.learning.LearningSkipReason;
import com.jongbeom.server.domain.notification.NotificationType;
import com.jongbeom.server.global.error.ErrorCode;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * API 문서 정합성 — 서버 코드와 docs/api/api.md, docs/api/openapi.yaml 이 어긋나면 빌드가 실패한다.
 * <ul>
 *   <li>엔드포인트 목록: 핸들러 매핑 == api.md 목록 표 == openapi.yaml paths</li>
 *   <li>DTO 필드: domain/*&#47;dto 와 global/error 의 record 필드 == openapi.yaml components.schemas 의 properties</li>
 *   <li>DTO 필드: 같은 record == api.md 의 "| 필드 |" 표(중첩 record 는 경로로 전개)</li>
 *   <li>필드 표를 생략한 앵커: 반드시 "…와 동일" 참조여야 하고, 그 참조가 가리키는 DTO 와 필드·제약이 같아야 한다</li>
 *   <li>enum 목록: ErrorCode, LearningSkipReason, CutoffResult.Status, NotificationType == openapi.yaml 의 enum</li>
 * </ul>
 * 설명·제약 문구는 검사하지 않는다(사람이 맞춘다). 문서 파일은 build.gradle 의 processTestResources 가 docs/api/ 로 복사한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiDocsConsistencyIT {

    /** api.md "엔드포인트 목록" 표의 행: | 화면 | 기능 | `METHOD` | `/path` | 인증 | */
    private static final Pattern MD_ENDPOINT_ROW = Pattern.compile(
            "^\\|[^|]*\\|[^|]*\\|\\s*`(GET|POST|PUT|DELETE|PATCH)`\\s*\\|\\s*`(/[^`]*)`\\s*\\|");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");

    /** api.md 필드 표의 앵커: {@code **요청 바디** `Dto`} / {@code **응답 200** `data: Dto`} (배열 표기 포함). */
    private static final Pattern MD_TABLE_ANCHOR = Pattern.compile(
            "^\\*\\*(?:요청 바디|응답 \\d{3})\\*\\*\\s+`(?:data:\\s*)?([A-Z][A-Za-z0-9]*)(?:\\[])?`");
    /** 앵커 줄의 필드 표 생략 참조: {@code `CreateCaffeineRecordRequest`)와 동일한 필드·제약} 의 DTO 이름. */
    private static final Pattern MD_TABLE_MIRROR = Pattern.compile("`([A-Z][A-Za-z0-9]*)`[^`]*와 동일");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final String BASE_PACKAGE = "com.jongbeom.server";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void 엔드포인트_목록은_코드_apimd_openapi_세곳이_같다() throws Exception {
        Set<String> code = codeEndpoints();
        assertThat(code).isNotEmpty();
        assertThat(markdownEndpoints()).as("docs/api/api.md 엔드포인트 목록 표").containsExactlyInAnyOrderElementsOf(code);
        assertThat(openapiEndpoints()).as("docs/api/openapi.yaml paths").containsExactlyInAnyOrderElementsOf(code);
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
    void api_md_필드_표는_DTO_레코드와_같다() throws Exception {
        Map<String, Set<String>> tables = markdownApi().tables();
        Map<String, Class<?>> records = recordsByName();
        assertThat(tables).as("docs/api/api.md 필드 표").isNotEmpty();
        for (Map.Entry<String, Set<String>> table : tables.entrySet()) {
            Class<?> record = lookup(records, table.getKey());
            assertThat(expandWildcards(table.getValue(), record)).as("docs/api/api.md %s 필드 표", table.getKey())
                    .containsExactlyInAnyOrderElementsOf(fieldPaths(record, "", tables.keySet()));
        }
    }

    /**
     * 앵커는 있는데 필드 표가 없으면 — 문서화를 빠뜨렸거나 "…와 동일"로 생략했거나 둘 중 하나다. 후자만 허용하고,
     * 그 한 문장이 참인지(필드 이름·순서와 검증 애노테이션까지) 여기서 지킨다. 표기는 api.md "필드 표 표기" 절 참조.
     */
    @Test
    void 필드_표를_생략한_앵커는_동일_참조이고_그_참조가_참이다() throws Exception {
        ApiMd md = markdownApi();
        Map<String, Class<?>> records = recordsByName();
        assertThat(md.anchors()).as("docs/api/api.md 필드 표 앵커").isNotEmpty();
        for (Anchor anchor : md.anchors()) {
            if (md.tables().containsKey(anchor.dto())) {
                continue;
            }
            assertThat(anchor.mirror())
                    .as("docs/api/api.md %s: 필드 표가 없다. 표를 쓰거나 `다른DTO`…와 동일 로 참조하라", anchor.dto())
                    .isNotNull();
            assertThat(md.tables()).as("docs/api/api.md %s 가 참조하는 %s 의 필드 표", anchor.dto(), anchor.mirror())
                    .containsKey(anchor.mirror());
            assertThat(fieldsWithConstraints(lookup(records, anchor.dto())))
                    .as("docs/api/api.md \"%s …와 동일\"", anchor.mirror())
                    .isEqualTo(fieldsWithConstraints(lookup(records, anchor.mirror())));
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
        assertThat(enumOf(schemas, "NotificationItem", "type"))
                .containsExactlyInAnyOrderElementsOf(names(NotificationType.values()));
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
        for (String line : read("docs/api/api.md").split("\n")) {
            Matcher matcher = MD_ENDPOINT_ROW.matcher(line);
            if (matcher.find()) {
                result.add(matcher.group(1) + " " + matcher.group(2));
            }
        }
        return result;
    }

    /** api.md 파싱 결과: DTO 이름 → 필드 경로 표, 그리고 필드 표 앵커 목록. */
    private record ApiMd(Map<String, Set<String>> tables, List<Anchor> anchors) {
    }

    /** 필드 표 앵커 한 줄. {@code mirror} 는 표를 생략하고 "…와 동일"로 가리킨 DTO (없으면 null). */
    private record Anchor(String dto, String mirror) {
    }

    /**
     * api.md 의 "| 필드 |" 표를 DTO 이름 → 필드 경로로 읽고, 앵커 줄도 함께 모은다. 경로 표기는
     * <code>a</code> · <code>a.b</code> · <code>a[].b</code>(→ <code>a.b</code>) · 슬래시 병기 ·
     * 타입 칸의 인라인 객체 <code>{ `x`, `y` }</code>(→ <code>a.x</code>, <code>a.y</code>).
     */
    private static ApiMd markdownApi() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<Anchor> anchors = new ArrayList<>();
        String dto = null;
        boolean inTable = false;
        for (String line : read("docs/api/api.md").split("\n")) {
            Matcher anchor = MD_TABLE_ANCHOR.matcher(line);
            if (anchor.find()) {
                dto = anchor.group(1);
                inTable = false;
                Matcher mirror = MD_TABLE_MIRROR.matcher(line.substring(anchor.end()));
                anchors.add(new Anchor(dto, mirror.find() ? mirror.group(1) : null));
            } else if (line.startsWith("#")) {
                dto = null;
                inTable = false;
            } else if (dto == null) {
                continue;
            } else if (line.startsWith("| 필드 ")) {
                inTable = true;
            } else if (!inTable) {
                continue;
            } else if (!line.startsWith("|")) {
                dto = null;
                inTable = false;
            } else if (!line.startsWith("|---")) {
                result.computeIfAbsent(dto, key -> new LinkedHashSet<>()).addAll(rowPaths(line));
            }
        }
        return new ApiMd(result, anchors);
    }

    /** 표 한 행 → 필드 경로들. 첫 칸의 백틱 토큰 + 타입 칸이 인라인 객체면 그 자식들. */
    private static List<String> rowPaths(String row) {
        String[] cells = row.split("\\|", -1);
        List<String> paths = new ArrayList<>();
        for (String token : backticked(cells.length > 1 ? cells[1] : "")) {
            paths.add(token.replace("[]", ""));
        }
        String type = cells.length > 2 ? cells[2].trim() : "";
        if (paths.size() == 1 && type.startsWith("{") && type.endsWith("}")) {
            String parent = paths.get(0);
            backticked(type).forEach(child -> paths.add(parent + "." + child));
        }
        return paths;
    }

    private static List<String> backticked(String cell) {
        List<String> result = new ArrayList<>();
        Matcher matcher = BACKTICKED.matcher(cell);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /** {@code *Histogram.lower} 처럼 * 로 시작하는 경로를 접미사가 맞는 최상위 필드들로 전개한다. */
    private static Set<String> expandWildcards(Collection<String> paths, Class<?> record) {
        List<String> top = Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
        Set<String> result = new TreeSet<>();
        for (String path : paths) {
            if (!path.startsWith("*")) {
                result.add(path);
                continue;
            }
            int dot = path.indexOf('.');
            String suffix = dot < 0 ? path.substring(1) : path.substring(1, dot);
            String rest = dot < 0 ? "" : path.substring(dot);
            top.stream().filter(name -> name.endsWith(suffix)).forEach(name -> result.add(name + rest));
        }
        return result;
    }

    /** record 필드를 경로로 전개. 자체 필드 표를 가진 DTO 에서 멈춘다 — 그 표가 따로 검사한다. */
    private static Set<String> fieldPaths(Class<?> record, String prefix, Set<String> documented) {
        Set<String> result = new TreeSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            String path = prefix + component.getName();
            result.add(path);
            Class<?> nested = elementType(component);
            if (nested != null && nested.isRecord() && !documented.contains(nested.getSimpleName())) {
                result.addAll(fieldPaths(nested, path + ".", documented));
            }
        }
        return result;
    }

    /** "필드명 [@애노테이션...]" 목록 (선언 순서). */
    private static List<String> fieldsWithConstraints(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(component -> component.getName() + " " + constraints(component))
                .toList();
    }

    /**
     * 컴포넌트에 붙은 애노테이션 (정렬·중복 제거). Jakarta Validation 은 {@code @Target} 에 RECORD_COMPONENT 가 없어
     * {@code getDeclaredAnnotations()} 에 안 잡히고 접근자 메서드와 타입에만 남는다 — 세 곳을 합쳐야 실제로 비교된다.
     */
    private static Set<String> constraints(RecordComponent component) {
        return Stream.of(component.getDeclaredAnnotations(),
                        component.getAccessor().getDeclaredAnnotations(),
                        component.getAnnotatedType().getAnnotations())
                .flatMap(Arrays::stream)
                .map(Annotation::toString)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** {@code List<T>} 면 T, 아니면 선언 타입. */
    private static Class<?> elementType(RecordComponent component) {
        if (Collection.class.isAssignableFrom(component.getType())
                && component.getGenericType() instanceof ParameterizedType parameterized) {
            Type argument = parameterized.getActualTypeArguments()[0];
            return argument instanceof Class<?> cls ? cls : null;
        }
        return component.getType();
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
        try (InputStream in = new ClassPathResource("docs/api/openapi.yaml").getInputStream()) {
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

    /** 이름 → DTO record. api.md 표·앵커가 이름으로 가리키므로 조회 실패는 문서 오타로 본다. */
    private static Map<String, Class<?>> recordsByName() throws Exception {
        return documentedRecords().stream()
                .collect(Collectors.toMap(Class::getSimpleName, cls -> cls, (first, second) -> first));
    }

    private static Class<?> lookup(Map<String, Class<?>> records, String name) {
        Class<?> record = records.get(name);
        assertThat(record).as("docs/api/api.md 가 참조하는 DTO %s 를 코드에서 못 찾음", name).isNotNull();
        return record;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
