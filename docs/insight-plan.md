# 인사이트 기획 — 카페인·수면 상관 리포트 + 기간별 섭취 통계

> 작성: 2026-10-16 · 상태: **기획만** (코드·API 문서·`features.md` 미변경)
> 대상: 서버 `domain/insight` 신설 + iOS `Features/Insight` 신설
> 출처: iOS 로드맵 `../../captest1/docs/feature-roadmap.md` §3.1·§4 7번, `feature/cafe-nearby-window` 브랜치 `docs/features.md` "예정" 절의 두 항목(기간별 섭취 통계·카페인·수면 상관 리포트)
>
> 착수할 때 이 문서의 API 초안을 `api/api.md`·`api/openapi.yaml`·`features.md`·`README.md`로 옮기고 구현과 함께 머지한다(`CLAUDE.md` "바꾸면 같이 바꿀 것").
> 이 문서는 그 전까지의 설계 메모이며, `ApiDocsConsistencyIT`가 대조하지 않는다.

---

## 1. 목적

앱이 지금 답하는 질문은 세 가지다. "지금 마셔도 되나?"(홈), "어젯밤 어떻게 잤나?"(수면), "내 반감기는?"(학습).
비어 있는 질문은 **"카페인이 내 잠에 실제로 어떤 영향을 줬고, 무엇을 바꾸면 되나?"** 이다.
학습 대시보드의 R²·RMSE는 모델 통계라서 사용자가 읽는 문장이 아니다. 이 기능은 같은 데이터를 **비교 두 개와 문장 한두 줄**로 바꿔 보여 준다.

### 사용자에게 보여 줄 문장 예시

| 상황 | 문장 |
|---|---|
| 잔량 비교, 차이 있음 | "취침 때 카페인이 50mg 넘게 남아 있던 밤은 잠드는 데 평균 **11분** 더 걸렸어요 (6밤 vs 8밤)" |
| 잔량 비교, 효율 | "그런 밤은 수면 효율도 평균 **4%p** 낮았어요" |
| 마감 비교 | "마감 시각을 지킨 밤은 평균 **14분** 만에 잠들었고, 넘긴 밤은 **27분** 걸렸어요" |
| 차이 작음 | "최근 14일은 취침 잔량에 따른 뚜렷한 차이가 아직 보이지 않아요" |
| 표본 부족 | "비교하려면 잔량이 높았던 밤이 **2밤** 더 필요해요" |
| 섭취 통계 | "최근 7일 하루 평균 **210mg**, 400mg을 넘긴 날은 **1일**이에요. 마지막 한 잔은 평균 **16시 40분**" |

문장은 **서버가 만든다**(thin-client 원칙, `../README.md`). 앱은 받은 문자열을 그대로 표시한다.
"~때문에", "유의미하게" 같은 인과·통계 단정 표현은 쓰지 않는다. 비교 결과를 "~했어요"로 서술만 한다.

---

## 2. 계산 방식

### 2.1 데이터 단위: 밤(night)

- 날짜 키는 학습과 같다: **그 밤이 끝나는 아침의 로컬 날짜**(`HalfLifeObservation.date`와 동일 규칙, `../src/main/java/com/jongbeom/server/domain/learning/entity/HalfLifeObservation.java`).
- 밤 데이터는 `SleepService.mergedNight(userId, date, zone)`을 재사용한다(`../src/main/java/com/jongbeom/server/domain/sleep/service/SleepService.java:80`). 학습이 이미 이 메서드를 쓰고 있어서 새 병합 로직이 필요 없다.
- 수면 지표는 `SleepSummaryCalc.compute(intervals, bedtimeStart)` 결과를 쓴다(`../src/main/java/com/jongbeom/server/domain/calc/SleepSummaryCalc.java:51`).
  - SOL(분): `sleepOnsetLatency`. Apple Watch가 없으면 null이다. null인 밤은 SOL 평균에서만 빠지고, 효율·총 수면 평균에는 들어간다.
  - 수면 효율: `sleepEfficiency`
  - 총 수면: `totalSleepSeconds`
  - 깊은 수면 비율: `stageBreakdown`의 deep ÷ 총 수면. 단계 데이터가 없으면 null.

> **관측 테이블(`half_life_observations`)을 쓰지 않는 이유.**
> 학습은 취침 잔량이 1mg 미만인 밤을 `INSUFFICIENT_CAFFEINE_RESIDUAL`로 건너뛰고(`BayesianHalfLifeUpdater.MIN_RESIDUAL_FOR_LEARNING = 1.0`, `../src/main/java/com/jongbeom/server/domain/calc/BayesianHalfLifeUpdater.java:32`), SOL이 없는 밤도 건너뛴다(`../src/main/java/com/jongbeom/server/domain/learning/LearningSkipReason.java`).
> 그래서 관측에는 **카페인을 안 마신 밤, 즉 비교의 대조군이 빠져 있다.** 리포트는 원시 수면 샘플과 카페인 기록에서 밤마다 직접 계산한다.

### 2.2 취침 시 잔량

- 취침 시각: 병합된 밤의 `bedtimeStart`(실제로 침대에 든 시각). 없으면 그 밤은 리포트에서 제외한다.
- 섭취 기록: 취침 직전 24시간 기록. 학습의 `dosesBefore`와 같은 창이다(`../src/main/java/com/jongbeom/server/domain/learning/service/LearningService.java`, `DOSES_LOOKBACK_HOURS = 24`). 조회는 기존 `CaffeineRecordRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc`를 쓴다.
- 잔량: `Pharmacokinetics.totalRemaining(bedtime, doses, halfLifeHours)`(`../src/main/java/com/jongbeom/server/domain/calc/Pharmacokinetics.java:85`).
- 반감기: **요청 시점의 유효 반감기 하나로 기간 전체를 계산한다.** `CaffeineService.today`가 쓰는 반감기 결정 로직(학습값/설정값 × 건강 상태 배수)을 그대로 재사용한다. 밤마다 그때의 prior를 쓰면 밤끼리 비교 기준이 달라지기 때문이다. → §7 미결 1

### 2.3 비교 A — 취침 잔량 기준

| 그룹 | 조건 |
|---|---|
| 높은 밤 | 취침 잔량 ≥ **50mg** |
| 낮은 밤 | 취침 잔량 < 50mg (카페인 0mg 밤 포함) |

- 50mg은 홈 화면 안전선과 같은 상수 `Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG`를 쓴다(`Pharmacokinetics.java:27`). 새 상수를 만들지 않는다.
- 그룹마다 SOL 평균·효율 평균·총 수면 평균·깊은 수면 비율 평균과 밤 수를 계산한다.
- 차이 = 높은 밤 평균 − 낮은 밤 평균.

### 2.4 비교 B — 마감 시각 전후

"마감을 지켰나"를 밤마다 다음처럼 판정한다.

1. 그날(로컬 05:00 경계, `LocalCalendar`) 마지막 섭취를 `last`라고 한다. 그날 섭취가 없으면 이 비교에서 **제외**한다(무카페인 밤은 비교 A가 다룬다).
2. `last` 직전까지의 기록으로 `Pharmacokinetics.cutoffTime(doses, bedtime, referenceDoseMg, halfLife, 50)`을 계산한다(`Pharmacokinetics.java:175`). 홈 화면 마감 시각과 같은 함수다.
3. 판정
   - `SAFE_ANYTIME` → 지킴
   - `CUTOFF` 이고 `last.timestamp ≤ cutoff` → 지킴, `>` → 넘김
   - `ALREADY_EXCEEDED`(마지막 잔 전에 이미 초과) → 넘김
- `referenceDoseMg`는 요청 시점 설정값을 쓴다. 비교 A와 같은 이유다.
- 그룹 지표는 비교 A와 같다.

### 2.5 최소 표본 수와 문장 규칙

| 규칙 | 값(제안) | 근거 |
|---|---|---|
| 리포트를 보여 줄 최소 밤 수 | 기간 내 수면 데이터가 있는 밤 **5밤** | 7일 기간에서도 대부분 채워지는 값 |
| 그룹당 최소 밤 수 | **3밤** | 학습 대시보드의 상세 표시 기준과 같다(`LearningStatsCalc.MIN_POINTS_FOR_R_SQUARED = 3`, 앱 "3회부터 상세 표시") |
| SOL 그룹당 최소 | SOL이 있는 밤 **3밤** | SOL null 밤이 많으면 효율 비교만 문장으로 낸다 |
| "차이 있음" 문장 기준 | SOL 차이 ≥ **5분**, 효율 차이 ≥ **3%p** | 그보다 작으면 "뚜렷한 차이 없음" 문장. 값은 §7 미결 2 |

- 한 그룹이라도 기준에 못 미치면 `sufficient = false`로 내리고, 모자란 밤 수 `moreNightsNeeded`와 표본 부족 문장을 함께 내린다.
- p값·신뢰구간은 내지 않는다. 표본이 작아서 수치가 오해를 부르고, 발표에서 설명할 수 있는 범위를 넘는다(§6).

### 2.6 기간별 섭취 통계

- 하루 경계는 홈과 같이 **로컬 05:00**이다(`LocalCalendar.chartStart`, `../src/main/java/com/jongbeom/server/domain/calc/LocalCalendar.java:32`).
- 날짜별로 총 섭취량, 기록 수, 첫·마지막 섭취 시각, 400mg 초과 여부(`Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG`)를 낸다.
- 요약에는 기록이 있는 날의 하루 평균, 최대, 400mg 초과 일수, 마지막 섭취 시각 평균(로컬 시:분)을 낸다.
- 기록이 없는 날은 `totalMg = 0`, `recordCount = 0`으로 포함해 차트 칸이 비지 않게 한다. 평균은 기록이 있는 날만으로 계산한다.

### 2.7 저장

DB 스키마는 바꾸지 않는다. 요청마다 계산한다(최대 31밤 × `mergedNight` 1회). 느리면 그때 캐시를 검토한다.

---

## 3. API 초안

공통 규칙은 `CLAUDE.md`를 따른다. `ApiResponse<T>` 봉투, `ZoneId tz` 바인딩, `CurrentUser.id(jwt)`.
인증이 필요한 엔드포인트라서 `SecurityConfig`에 따로 등록하지 않는다(미등록이면 authenticated).

### 3.1 엔드포인트

| 메서드 | 경로 | 기능 이름(`features.md`용) |
|---|---|---|
| GET | `/api/insight/intake-stats?from=2026-10-01&to=2026-10-14&tz=Asia/Seoul` | 기간별 섭취 통계 |
| GET | `/api/insight/caffeine-sleep?from=2026-10-01&to=2026-10-14&tz=Asia/Seoul` | 카페인·수면 상관 리포트 |

- `from`·`to`: `LocalDate`(ISO DATE), 양 끝 포함. `/api/sleep/summary`의 `date` 바인딩과 같은 방식이다(`../src/main/java/com/jongbeom/server/domain/sleep/controller/SleepController.java`).
- 검증: `from ≤ to`, 기간 ≤ **31일**, `to ≤ 오늘(tz 기준)`. 위반하면 기존 `INVALID_PARAMETER`(400)로 응답한다. 새 ErrorCode는 만들지 않는다.
- 앱은 7·14·30일 프리셋만 보낸다.

### 3.2 DTO (record)

배치: `domain/insight/dto/`.

```java
public record IntakeStatsResponse(
        LocalDate from,
        LocalDate to,
        int dayCount,                 // 기간 일수
        int recordedDayCount,         // 기록이 있는 날 수
        double averageDailyMg,        // 기록 있는 날 평균
        double maxDailyMg,
        int overLimitDayCount,        // 400mg 초과 일수
        Integer averageLastIntakeMinuteOfDay,  // 마지막 섭취 로컬 시각 평균(0~1439), 기록 없으면 null
        String summarySentence,
        List<DayItem> days
) {
    public record DayItem(
            LocalDate date,
            double totalMg,
            int recordCount,
            Instant firstIntakeAt,    // 없으면 null
            Instant lastIntakeAt,     // 없으면 null
            boolean overLimit
    ) {}
}
```

```java
public record CaffeineSleepReportResponse(
        LocalDate from,
        LocalDate to,
        int nightCount,               // 수면 데이터가 있는 밤
        boolean sufficient,           // 최소 5밤 충족
        double residualThresholdMg,   // 50.0 (앱 차트·문구용)
        double halfLifeHoursUsed,     // 계산에 쓴 유효 반감기
        Comparison residualComparison,   // 비교 A: high = 잔량 ≥ 50, low = < 50
        Comparison cutoffComparison,     // 비교 B: high = 넘김, low = 지킴
        List<String> headlines,          // 화면 상단 문장 0~2개
        List<NightItem> nights
) {
    public record Comparison(
            Group high,
            Group low,
            boolean sufficient,
            Integer moreNightsNeeded,   // sufficient면 null
            Double solDiffMinutes,      // high − low, 계산 불가면 null
            Double efficiencyDiff,      // high − low (0~1 스케일), 계산 불가면 null
            String sentence
    ) {}

    public record Group(
            int nightCount,
            int solNightCount,
            Double avgSolMinutes,
            Double avgSleepEfficiency,
            Double avgTotalSleepMinutes,
            Double avgDeepSleepRatio
    ) {}

    public record NightItem(
            LocalDate date,
            Instant bedtime,
            double residualAtBedtimeMg,
            Instant lastIntakeAt,        // 그날 섭취 없으면 null
            Boolean cutoffKept,          // 섭취 없으면 null
            Double solMinutes,
            double sleepEfficiency,
            double totalSleepMinutes,
            Double deepSleepRatio
    ) {}
}
```

- 단위는 기존 응답과 맞춘다. 시각은 UTC `Instant`, 효율은 0~1. 기존 `SleepSummaryResponse`는 초 단위지만, 리포트는 문장과 같은 단위인 **분**으로 내린다. 필드 이름에 `Minutes`를 붙여 구분한다.
- `nights`는 산점도(잔량 × SOL)와 밤별 목록에 쓴다.

### 3.3 서버 배치

| 파일 | 역할 |
|---|---|
| `domain/insight/controller/InsightController.java` | 두 GET, 파라미터 검증 |
| `domain/insight/service/InsightService.java` | 기간 루프, `SleepService.mergedNight`·`CaffeineRecordRepository`·`UserSettingsService` 조합 |
| `domain/insight/dto/*` | §3.2 |
| `domain/calc/InsightCalc.java` | 순수 계산: 그룹 나누기, 평균, 마감 판정, 표본 기준, 차이 판정. `Clock`·DB에 의존하지 않음 |
| 테스트 | `calc/InsightCalcTest`(골든 값), `insight/InsightControllerIT`(기간 검증·사용자 격리·표본 부족) |

- 문장 생성은 `InsightService`(또는 같은 패키지의 `InsightSentences`)에 둔다. `calc`에는 숫자만 둔다.
- `calc`의 기존 상수(`CLAUDE.md` 절대 규칙)는 읽기만 하고 바꾸지 않는다.

---

## 4. 앱 화면 구성 — `Features/Insight`

iOS 의존 규칙(`../../captest1/CLAUDE.md`)을 따른다. Core는 Features를 모르고, View는 Store만 호출하고, 엔드포인트는 `APIClient+X` 확장에 둔다.

```text
Features/Insight/
├─ Views/
│  ├─ InsightView.swift              기간 선택 + 아래 카드들
│  ├─ InsightHeadlineCard.swift      headlines 문장 (최대 2줄)
│  ├─ ComparisonCard.swift           비교 A·B 공용: 두 그룹 SOL·효율 막대 + sentence
│  ├─ ResidualSolScatter.swift       nights 산점도 (x 잔량, y SOL, 50mg 세로선)
│  ├─ IntakeTrendChart.swift         days 막대 + 400mg 기준선 + summarySentence
│  └─ InsufficientDataCard.swift     sufficient=false: "N밤 더 필요해요"
├─ Store/
│  └─ InsightStore.swift             period(7/14/30), intakeStats, report, isLoading, errorMessage
└─ Data/
   ├─ InsightRepository.swift        protocol (테스트 페이크용)
   ├─ APIClient+Insight.swift        두 GET
   └─ InsightDTO.swift               §3.2 미러
```

- 화면 흐름: 기간 선택(기본 14일) → 상단 문장 → 비교 A 카드 → 비교 B 카드 → 산점도 → 섭취 추이.
- 진입 위치: 탭을 하나 추가할지, 수면 분석 탭 상단에 "카페인과 수면" 카드를 두고 push할지 정한다. → §7 미결 3
- 빈 상태·오류 상태는 홈의 `CutoffUnavailableHero` 패턴을 따른다(`../../captest1/captest1/Features/Caffeine/Views/HomeView.swift`). 실패를 "차이 없음"처럼 보이게 하지 않는다.
- 단위 테스트: `captest1Tests/InsightStoreTests.swift`. 기존 `Fakes/`의 페이크 Repository 패턴을 쓴다.
- 차트는 기존 `CaffeineChartView`·`CalibrationScatterChart`와 같이 Swift Charts로 그리고, 색은 `Theme` 토큰만 쓴다.

---

## 5. 선행 작업

| # | 작업 | 지금 | 리포트에 필요한 이유 | 범위 |
|---|---|---|---|---|
| 1 | **학습 자동 실행** | 학습 탭을 열 때만 `/api/learning/run`을 호출한다(`../../captest1/captest1/Features/Learning/Views/LearningDashboardView.swift:55`의 `.task`) | 리포트 잔량은 유효 반감기로 계산한다(§2.2). 학습이 안 돌면 그 반감기가 갱신되지 않아 잔량 값이 낡는다. 학습 탭과 리포트가 서로 다른 반감기를 말하게 된다 | 앱: `App/ContentView.swift:51` `.task`에서 `sleepStore.loadDataIfNeeded()` 직후 학습 실행 호출로 이동 |
| 2 | 수면 업로드 기간 | 앱은 실행할 때마다 최근 **7일**만 업로드한다(`AppConstants.Sleep.recentHistoryDays = 7`, `../../captest1/captest1/Core/Model/AppConstants.swift:17`, `SleepStore.swift:41`) | 서버에 이미 올라간 샘플은 남는다. 그래서 주 1회 이상 앱을 연 사용자는 30일이 쌓이지만, 첫 사용자는 14·30일 리포트가 7일치뿐이다 | 앱: 최초 1회 30일 백필 또는 기간 확대. 증분 조회(로드맵 §2 (17))와 같이 검토 |
| 3 | 서버 문서 브랜치 정리 | "예정" 목록이 `feature/cafe-nearby-window`의 `docs/features.md`에만 있고, main은 "예정 항목 없음"이다 | 착수할 때 `features.md`에 기능을 등록할 기준 브랜치가 필요하다 | 문서: 브랜치 병합 후 등록 |

---

## 6. 범위 밖

| 항목 | 이유 / 어디서 다루나 |
|---|---|
| 통계 검정(p값·신뢰구간), 회귀 | 표본이 작다. 문장은 비교 서술만 한다 |
| 개인 카페인 민감도(100mg당 SOL +N분) | 학습 모델의 `BETA` 고정을 건드리는 별도 주제. 로드맵 §3.1 |
| 교란 요인 보정(음주·낮잠·운동 태그) | 로드맵 §3.3. 태그가 생기면 비교에서 제외하는 필터만 추가 |
| 아침 체크인(주관 SOL) | 로드맵 §3.3. 생기면 SOL 소스로 합칠 수 있음 |
| 섭취 시뮬레이션·안전 섭취량 역계산·감량 플랜 | `features.md` 예정의 다른 항목 |
| 주간 리포트 알림·위젯 | 서버는 푸시를 보내지 않는다(`CLAUDE.md` 도메인 계약). 필요하면 알림 계획에 종류를 추가하는 별도 작업 |
| 리포트 스냅샷 저장·캐시 테이블 | §2.7. 요청마다 계산 |
| `calc` 상수 변경, 학습 알고리즘 변경 | `CLAUDE.md` 절대 규칙 |
| 기간 31일 초과, 자유 기간 선택 UI | 7·14·30일 프리셋만 |

---

## 7. 미결 사항

1. **잔량 계산 반감기**: 요청 시점 유효 반감기 하나로 할지(제안), 밤마다 그때의 학습값으로 할지. 후자는 관측 테이블의 `predicted_residual`과 같아지지만, 대조군 밤에는 그 값이 없다.
2. **"차이 있음" 기준값**: SOL 5분 / 효율 3%p 제안. 값은 `InsightCalc` 상수로 두고 골든 테스트에 포함한다.
3. **앱 진입 위치**: 5번째 탭으로 추가할지, 수면 분석 탭 안의 카드에서 push할지.
4. **Watch 없는 시연 환경**: SOL이 전부 null이면 비교는 효율만 남는다. 시드 데이터로 할지, 아침 체크인을 먼저 할지 정해야 한다(로드맵 §6과 같은 질문).
5. **비교 B의 기준 섭취량**: 요청 시점 설정값을 쓴다. 기간 중에 설정을 바꾼 사용자는 과거 판정이 당시 화면과 다를 수 있다. 문구로 안내할지 정한다.

---

## 8. 관련 문서

- iOS 로드맵: `../../captest1/docs/feature-roadmap.md` (§3.1 인사이트, §4 착수 순서, §5 배치 메모)
- 서버 규칙: `../CLAUDE.md`, 문서 색인: `README.md`
- 학습 수식·상수: `../src/main/java/com/jongbeom/server/domain/calc/BayesianHalfLifeUpdater.java`, `LearningStatsCalc.java`
