package com.jongbeom.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 도메인 모듈 경계(CLAUDE.md "모듈 경계"). 패키지 구조는 컴파일러가 막아 주지 않으므로 의존 방향을 여기서 대조한다.
 * 컴파일된 main 클래스만 읽고 Spring 을 띄우지 않는다.
 */
@AnalyzeClasses(packages = "com.jongbeom.server", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String DOMAIN_PREFIX = "com.jongbeom.server.domain.";
    /** 모든 도메인이 쓰는 순수 연산. 레이어가 없어 하위 패키지 전체가 공개다. */
    private static final String SHARED_KERNEL = "calc";
    /** 다른 도메인에 공개되는 레이어. 도메인 루트(레이어 없음)의 타입도 공개다. */
    private static final Set<String> PUBLIC_LAYERS = Set.of("service");
    /**
     * 내부 접근을 허용하는 예외(쓰는 쪽 → 대상). auth 는 user 테이블을 다루는 유일한 쓰기 경로라
     * user 의 애플리케이션 계층으로 본다 — 서비스를 한 겹 더 두면 위임만 하는 메서드가 생긴다.
     */
    private static final Map<String, Set<String>> INTERNAL_ACCESS_ALLOWED = Map.of("auth", Set.of("user"));
    /**
     * 모듈 의존 그래프(쓰는 쪽 → 쓰이는 쪽) — 모든 도메인이 있고 실제 의존과 정확히 같다. 새 연결은 여기 적어야 통과하므로
     * 리뷰에서 보이고, 끊긴 연결은 지워야 통과하므로 이 표가 곧 현재 구조다.
     * calc(공유 커널)·global(인프라)은 모든 도메인이 쓸 수 있어 적지 않는다.
     */
    private static final Map<String, Set<String>> MODULE_DEPENDENCIES = Map.of(
            "user", Set.of(),
            "settings", Set.of("user"),
            "sleep", Set.of("user"),
            "caffeine", Set.of("settings"),
            "notification", Set.of("settings", "caffeine"),
            "learning", Set.of("settings", "sleep", "caffeine", "user"),
            "insight", Set.of("settings", "sleep", "caffeine"),
            "auth", Set.of("user", "settings"));

    /** iOS 포팅 수식이 프레임워크·저장 방식과 무관하게 골든 테스트만으로 검증되도록. */
    @ArchTest
    static final ArchRule calc_dependsOnlyOnJdkAndItself = classes()
            .that().resideInAPackage(DOMAIN_PREFIX + "calc..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN_PREFIX + "calc..");

    @ArchTest
    static final ArchRule domains_haveNoCycles = slices()
            .matching(DOMAIN_PREFIX + "(*)..")
            .should().beFreeOfCycles();

    /** 인프라는 도메인을 모른다 — 도메인에 묶인 설정은 그 도메인에 둔다(JwtConfig 는 auth). */
    @ArchTest
    static final ArchRule global_doesNotDependOnDomains = noClasses()
            .that().resideInAPackage("com.jongbeom.server.global..")
            .should().dependOnClassesThat().resideInAPackage(DOMAIN_PREFIX + "(**)");

    /**
     * 다른 도메인에서는 service 와 도메인 루트 타입만 쓴다. repository·entity 는 저장 방식이고
     * dto 는 API 문서와 대조되는 HTTP 계약이라, 밖에서 쓰면 한쪽 사정으로 다른 쪽이 바뀐다.
     */
    @ArchTest
    static final ArchRule domains_useOtherDomainsThroughPublicSurface = classes()
            .that().resideInAPackage(DOMAIN_PREFIX + "(**)")
            .should(onlyUsePublicSurfaceOfOtherDomains());

    /** 모듈 사이 연결은 {@link #MODULE_DEPENDENCIES}에 적은 것만. 위반 메시지에 어느 클래스의 어떤 참조인지 나온다. */
    @ArchTest
    static final ArchRule domains_dependOnlyOnDeclaredModules = classes()
            .that().resideInAPackage(DOMAIN_PREFIX + "(**)")
            .should(onlyDependOnDeclaredModules());

    /** 표가 실제 구조와 같은지 — 모든 도메인이 표에 있고, 쓰지 않는 연결이 표에 남아 있지 않다. */
    @ArchTest
    static void moduleDependencies_matchActualGraph(JavaClasses classes) {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JavaClass origin : classes) {
            if (!origin.getPackageName().startsWith(DOMAIN_PREFIX) || domainOf(origin).equals(SHARED_KERNEL)) {
                continue;
            }
            Set<String> used = actual.computeIfAbsent(domainOf(origin), domain -> new TreeSet<>());
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                otherModule(origin, dependency).ifPresent(used::add);
            }
        }

        assertThat(actual.keySet())
                .as("모든 도메인을 MODULE_DEPENDENCIES 에 적는다(쓰는 도메인이 없으면 빈 Set)")
                .containsExactlyInAnyOrderElementsOf(MODULE_DEPENDENCIES.keySet());
        Map<String, Set<String>> unused = new TreeMap<>();
        MODULE_DEPENDENCIES.forEach((from, declared) -> {
            Set<String> stale = new TreeSet<>(declared);
            stale.removeAll(actual.getOrDefault(from, Set.of()));
            if (!stale.isEmpty()) {
                unused.put(from, stale);
            }
        });
        assertThat(unused)
                .as("더 이상 쓰지 않는 연결은 MODULE_DEPENDENCIES 에서 지운다")
                .isEmpty();
    }

    private static ArchCondition<JavaClass> onlyDependOnDeclaredModules() {
        return new ArchCondition<>("depend only on modules declared in MODULE_DEPENDENCIES") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = domainOf(origin);
                Set<String> declared = MODULE_DEPENDENCIES.getOrDefault(from, Set.of());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    otherModule(origin, dependency)
                            .filter(to -> !declared.contains(to))
                            .ifPresent(to -> events.add(SimpleConditionEvent.violated(dependency,
                                    "표에 없는 모듈 의존 %s → %s (의도한 연결이면 MODULE_DEPENDENCIES 에 추가): %s"
                                            .formatted(from, to, dependency.getDescription()))));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyUsePublicSurfaceOfOtherDomains() {
        return new ArchCondition<>("use other domains only through their service layer or root types") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Set<String> internalAccessAllowed = INTERNAL_ACCESS_ALLOWED.getOrDefault(domainOf(origin), Set.of());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    otherModule(origin, dependency)
                            .filter(to -> !isPublic(dependency.getTargetClass()) && !internalAccessAllowed.contains(to))
                            .ifPresent(to -> events.add(
                                    SimpleConditionEvent.violated(dependency, dependency.getDescription())));
                }
            }
        };
    }

    /** 의존 대상이 다른 도메인이면 그 이름. 같은 도메인·calc(공유 커널)·도메인 밖(global·JDK·라이브러리)이면 빈 값. */
    private static Optional<String> otherModule(JavaClass origin, Dependency dependency) {
        JavaClass target = dependency.getTargetClass();
        if (!target.getPackageName().startsWith(DOMAIN_PREFIX)) {
            return Optional.empty();
        }
        String to = domainOf(target);
        return to.equals(domainOf(origin)) || to.equals(SHARED_KERNEL) ? Optional.empty() : Optional.of(to);
    }

    /** {@code com.jongbeom.server.domain.<도메인>[.<레이어>...]} 의 도메인 이름. */
    private static String domainOf(JavaClass javaClass) {
        return segmentsAfterDomainPrefix(javaClass)[0];
    }

    private static boolean isPublic(JavaClass javaClass) {
        String[] segments = segmentsAfterDomainPrefix(javaClass);
        return segments.length == 1 || PUBLIC_LAYERS.contains(segments[1]);
    }

    private static String[] segmentsAfterDomainPrefix(JavaClass javaClass) {
        return javaClass.getPackageName().substring(DOMAIN_PREFIX.length()).split("\\.");
    }
}
