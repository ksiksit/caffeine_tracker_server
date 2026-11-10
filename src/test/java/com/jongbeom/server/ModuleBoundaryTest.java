package com.jongbeom.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;

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

    private static ArchCondition<JavaClass> onlyUsePublicSurfaceOfOtherDomains() {
        return new ArchCondition<>("use other domains only through their service layer or root types") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = domainOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.getPackageName().startsWith(DOMAIN_PREFIX)) {
                        continue;
                    }
                    String to = domainOf(target);
                    if (to.equals(from) || to.equals(SHARED_KERNEL) || isPublic(target)
                            || INTERNAL_ACCESS_ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                }
            }
        };
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
