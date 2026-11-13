# 인사이트 기획 — 카페인·수면 상관 리포트 + 기간별 섭취 통계

> 작성: 2026-10-16 · 갱신: 2026-11-13(iOS 요청 응답 계약 §3.4, 미결 추천안 §7) · 상태: **기획만** (코드·API 문서·`features.md` 미변경)
>
> API 문서(`api/api.md`·`api/openapi.yaml`)에는 구현과 같은 PR에서 옮긴다. `ApiDocsConsistencyIT`는 컨트롤러가 없는데 문서에만 있는
> 엔드포인트와 DTO를 허용하지 않는다. 그 전까지 API 형태의 원본은 이 문서 §3이다.
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
- 밤 데이터는 `SleepService.mergedNight(userId, date, zone)`을 재사용한다(`../src/main/java/com/jongbeom/server/domain/sleep/service/SleepService.java`). 학습이 이미 이 메서드를 쓰고 있어서 새 병합 로직이 필요 없다.
- 수면 지표는 `SleepSummaryCalc.compute(intervals, bedtimeStart)` 결과를 쓴다(`../src/main/java/com/jongbeom/server/domain/calc/sleep/SleepSummaryCalc.java`).
  - SOL(분): `sleepOnsetLatency`. Apple Watch가 없으면 null이다. null인 밤은 SOL 평균에서만 빠지고, 효율·총 수면 평균에는 들어간다.
  - 수면 효율: `sleepEfficiency`
  - 총 수면: `totalSleepSeconds`
  - 깊은 수면 비율: `stageBreakdown`의 deep ÷ 총 수면. 단계 데이터가 없으면 null.

> **관측 테이블(`half_life_observations`)을 쓰지 않는 이유.**
> 학습은 취침 잔량이 1mg 미만인 밤을 `INSUFFICIENT_CAFFEINE_RESIDUAL`로 건너뛰고(`BayesianHalfLifeUpdater.MIN_RESIDUAL_FOR_LEARNING = 1.0`, `../src/main/java/com/jongbeom/server/domain/calc/learning/BayesianHalfLifeUpdater.java`), SOL이 없는 밤도 건너뛴다(`../src/main/java/com/jongbeom/server/domain/learning/LearningSkipReason.java`).
> 그래서 관측에는 **카페인을 안 마신 밤, 즉 비교의 대조군이 빠져 있다.** 리포트는 원시 수면 샘플과 카페인 기록에서 밤마다 직접 계산한다.

### 2.2 취침 시 잔량

- 취침 시각: 병합된 밤의 `bedtimeStart`(실제로 침대에 든 시각, 원시 inBed 샘플의 최초 시작). 수면 데이터가 없거나 `bedtimeStart`가 null이면
  그 밤은 리포트 전체(비교 A·B, `nightCount`, `nights`)에서 제외한다. 학습은 이때 설정 취침 시각으로 폴백하지만(`BedtimeExtractor`) 리포트는 폴백하지 않는다.
- 섭취 기록: 취침 직전 24시간 기록. 학습의 `dosesBefore`와 같은 창이다(`../src/main/java/com/jongbeom/server/domain/learning/service/LearningService.java`, `BayesianHalfLifeUpdater.DOSES_LOOKBACK_HOURS = 24`). 조회는 기존 `CaffeineRecordRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc`를 쓴다.
- 잔량: `Pharmacokinetics.totalRemaining(bedtime, doses, halfLifeHours)`(`../src/main/java/com/jongbeom/server/domain/calc/pk/Pharmacokinetics.java`).
- 반감기: **요청 시점의 유효 반감기 하나로 기간 전체를 계산한다.** `CaffeineService.today`가 쓰는 반감기 결정 로직(학습값/설정값 × 건강 상태 배수)을 그대로 재사용한다. 밤마다 그때의 prior를 쓰면 밤끼리 비교 기준이 달라지기 때문이다. → §7 ★1 (사용자 결정 대기)

### 2.3 비교 A — 취침 잔량 기준

| 그룹 | 조건 |
|---|---|
| 높은 밤 | 취침 잔량 ≥ **50mg** |
| 낮은 밤 | 취침 잔량 < 50mg (카페인 0mg 밤 포함) |

- 50mg은 홈 화면 안전선과 같은 상수 `Pharmacokinetics.BEDTIME_SAFE_THRESHOLD_MG`를 쓴다. 새 상수를 만들지 않는다.
- 그룹마다 SOL 평균·효율 평균·총 수면 평균·깊은 수면 비율 평균과 밤 수를 계산한다.
- 차이 = 높은 밤 평균 − 낮은 밤 평균.

### 2.4 비교 B — 마감 시각 전후

"마감을 지켰나"를 밤마다 다음처럼 판정한다.

1. 그 밤과 짝지은 섭취일(로컬 05:00 경계, `LocalCalendar`. 짝짓기 규칙은 §7 ★5)의 마지막 섭취를 `last`라고 한다. 그날 섭취가 없으면 이 비교에서 **제외**한다(무카페인 밤은 비교 A가 다룬다).
2. `last` 직전까지의 기록으로 `Pharmacokinetics.cutoffTime(doses, bedtime, referenceDoseMg, halfLife, 50)`을 계산한다. 홈 화면 마감 시각과 같은 함수다.
3. 판정
   - `SAFE_ANYTIME` → 지킴
   - `CUTOFF` 이고 `last.timestamp ≤ cutoff` → 지킴, `>` → 넘김
   - `ALREADY_EXCEEDED`(마지막 잔 전에 이미 초과) → 넘김
- `referenceDoseMg`는 요청 시점 설정값을 쓴다. 비교 A와 같은 이유다. → §7 ★4 (사용자 결정 대기)
- 그룹 지표는 비교 A와 같다.

### 2.5 최소 표본 수와 문장 규칙

아래 값은 모두 **추천안 · 사용자 결정 대기**다(§7 ★2·★3).

| 규칙 | 값(제안) | 근거 |
|---|---|---|
| 리포트를 보여 줄 최소 밤 수 | 기간 내 수면 데이터가 있는 밤 **5밤** | 7일 기간에서도 대부분 채워지는 값 |
| 그룹당 최소 밤 수 | **3밤** | 학습 대시보드의 상세 표시 기준과 같다(`LearningStatsCalc.MIN_POINTS_FOR_R_SQUARED = 3`, 앱 "3회부터 상세 표시") |
| SOL 그룹당 최소 | SOL이 있는 밤 **3밤** | SOL null 밤이 많으면 효율 비교만 문장으로 낸다 |
| "차이 있음" 문장 기준 | SOL 차이 ≥ **5분**, 효율 차이 ≥ **3%p** | 그보다 작으면 "뚜렷한 차이 없음" 문장. SOL 5분은 학습 모델 기울기 `BETA = 0.10`분/mg × 50mg와 같다 |

- 한 그룹이라도 기준에 못 미치면 `sufficient = false`로 내리고, 모자란 밤 수 `moreNightsNeeded`와 표본 부족 문장을 함께 내린다(정의는 §7-6).
- 리포트 최소 밤 수(5밤)를 못 채워도 응답 형태는 같다. 최상위 `sufficient = false`, 두 `Comparison`도 `sufficient = false`이고, `nights`는 그대로 내린다(§7-7).
- p값·신뢰구간은 내지 않는다. 표본이 작아서 수치가 오해를 부르고, 발표에서 설명할 수 있는 범위를 넘는다(§6).

### 2.6 기간별 섭취 통계

- 하루 경계는 홈과 같이 **로컬 05:00**이다(`LocalCalendar.chartStart`, `../src/main/java/com/jongbeom/server/domain/calc/time/LocalCalendar.java`).
- 날짜별로 총 섭취량, 기록 수, 첫·마지막 섭취 시각, 400mg 초과 여부(`Pharmacokinetics.DAILY_RECOMMENDED_LIMIT_MG`)를 낸다.
- 요약에는 기록이 있는 날의 하루 평균, 최대, 400mg 초과 일수, 마지막 섭취 시각 평균(로컬 시:분)을 낸다.
- 기록이 없는 날은 `totalMg = 0`, `recordCount = 0`으로 포함해 차트 칸이 비지 않게 한다. 평균은 기록이 있는 날만으로 계산한다.
- 기록이 있는 날이 0일이면 평균·최대는 null이다(§7-11).
- 오늘(`to`가 오늘인 경우)은 `days`에 넣되 아직 끝나지 않은 날이므로 요약 평균·최대·초과 일수에서는 뺀다(§7-9).
- 마지막 섭취 시각 평균은 05:00 경계 기준 경과 분으로 평균한 뒤 로컬 시:분으로 되돌린다. 새벽 1시 섭취가 0시 기준 평균을 끌어내리지 않게 하기 위해서다(§7-10).

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
  지금 `INVALID_PARAMETER`는 바인딩 형식 오류에만 쓰이므로, 기간 검증은 `insight/exception/`에 `BusinessException` 상속 예외를 두고
  같은 ErrorCode를 넘긴다(§7-8). api.md 에러 코드 표의 "쓰는 곳" 칸에 기간 검증을 추가한다.
- `to = 오늘`이면 리포트는 오늘 아침에 끝난 밤까지 포함한다. 날짜 키가 아침 날짜라서 오늘 밤은 아직 키가 없다. 섭취 통계의 오늘은 §2.6을 따른다.
- 앱은 7·14·30일 프리셋만 보낸다.

### 3.2 DTO (record)

배치: `domain/insight/dto/`.

```java
public record IntakeStatsResponse(
        LocalDate from,
        LocalDate to,
        int dayCount,                 // 기간 일수
        int recordedDayCount,         // 기록이 있는 날 수
        Double averageDailyMg,        // 기록 있는 날 평균(오늘 제외), 그런 날이 없으면 null
        Double maxDailyMg,            // 같은 조건, 없으면 null
        int overLimitDayCount,        // 400mg 초과 일수(오늘 제외)
        Integer averageLastIntakeMinuteOfDay,  // 마지막 섭취 로컬 시각 평균(벽시계 0~1439, 계산은 05:00 기준), 기록 없으면 null
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
        int nightCount,               // 수면 데이터와 bedtimeStart가 있는 밤 = nights.size()
        boolean sufficient,           // 최소 5밤 충족. false여도 아래 필드는 모두 채워 내린다
        double residualThresholdMg,   // 50.0 (앱 차트·문구용)
        double halfLifeHoursUsed,     // 계산에 쓴 유효 반감기(요청 시점 값, 학습 탭 관측값과 다를 수 있다)
        Comparison residualComparison,   // 비교 A: high = 잔량 ≥ 50, low = < 50
        Comparison cutoffComparison,     // 비교 B: high = 넘김, low = 지킴
        List<String> headlines,          // 화면 상단 문장 0~2개
        List<NightItem> nights           // sufficient와 무관하게 항상 내림, 날짜 오름차순
) {
    public record Comparison(
            Group high,
            Group low,
            boolean sufficient,
            Integer moreNightsNeeded,   // 두 그룹이 각각 3밤에 모자란 수의 합, sufficient면 null (§7-6)
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
            LocalDate date,              // 밤이 끝나는 아침의 로컬 날짜(수면 요약 date와 같다)
            Instant bedtime,             // bedtimeStart
            double residualAtBedtimeMg,  // mg, 반올림하지 않음. 취침 전 24h 기록이 없으면 0 (null 아님)
            double dayTotalMg,           // 짝지은 섭취일(05:00 경계)의 섭취 총량, 없으면 0 (§3.4)
            Instant lastIntakeAt,        // 짝지은 섭취일 섭취 없으면 null
            Boolean cutoffKept,          // 섭취 없으면 null
            Double solMinutes,           // 같은 date의 수면 요약 sleepOnsetLatencySeconds ÷ 60, 없으면 null
            double sleepEfficiency,
            double totalSleepMinutes,
            Double deepSleepRatio
    ) {}
}
```

- 단위는 기존 응답과 맞춘다. 시각은 UTC `Instant`, 효율은 0~1. 기존 `SleepSummaryResponse`는 초 단위지만, 리포트는 문장과 같은 단위인 **분**으로 내린다. 필드 이름에 `Minutes`를 붙여 구분한다.
- `nights`는 산점도(잔량 × SOL)와 밤별 목록에 쓴다.

### 3.4 `nights` 응답 계약 (iOS 요청, 2026-11-13)

리포트 화면 밖에서도 이 응답을 쓴다.

| 앱 화면 | 쓰는 필드 | 로드맵 |
|---|---|---|
| 수면 탭 "지난밤" 카드의 SOL + 취침 잔량 | `nights[]` 마지막 항목의 `residualAtBedtimeMg`·`solMinutes` | `../../captest1/docs/feature-roadmap.md` §2 (1) |
| 최근 7일 밤별 카페인 | `nights[]`의 `date`·`residualAtBedtimeMg`·`dayTotalMg` | 같은 문서 §2 (6) |

그래서 다음을 계약으로 둔다.

| 항목 | 계약 |
|---|---|
| 표본 부족 | `sufficient = false`(5밤 미만)여도 `nights`는 항상 내린다. 지난밤 카드는 1밤만 있어도 그려야 한다 |
| 제외 | 수면 데이터가 없거나 `bedtimeStart`가 null인 밤은 `nights`에 없다(§2.2). 앱은 빠진 날짜를 "데이터 없음"으로 표시한다 |
| `residualAtBedtimeMg` | number, mg, 반올림하지 않은 소수. 취침 전 24시간 기록이 없는 밤은 **0**(null 아님) |
| `solMinutes` | 같은 `date`의 `GET /api/sleep/summary` 응답 `sleepOnsetLatencySeconds`를 60으로 나눈 값이다. 같은 `SleepSummaryCalc`·같은 `bedtimeStart`로 계산한다. null 조건도 같다 |
| 반감기 | 잔량은 요청 시점 유효 반감기 하나로 계산한다(§2.2, `halfLifeHoursUsed`). 학습 탭 관측의 `predictedResidualAtBedtime`은 그 밤 학습 당시 prior로 계산하므로 **같은 밤이라도 값이 다를 수 있다**. 앱은 두 값을 한 화면에 나란히 두지 않는다 |
| `dayTotalMg` | 그 밤과 짝지은 섭취일(05:00 경계)의 섭취 총량이다. 짝짓기 규칙은 §7 ★5를 따른다(결정 대기) |

`dayTotalMg`는 **넣는 것을 추천**한다. 근거는 다음과 같다.
- 섭취 통계 `days[].date`는 섭취일, `nights[].date`는 아침 날짜라서 키가 하루 어긋난다. 앱이 둘을 이으면 짝짓기 규칙을 클라이언트가 다시 구현하게 되어 thin-client 원칙에 어긋난다.
- 서버는 비교 B 판정을 위해 그 섭취일 기록을 이미 조회하므로 추가 쿼리가 없다.
- 단점: ★5 결정에 값의 의미가 묶인다. ★5가 바뀌면 이 필드의 정의도 함께 바뀐다.

### 3.3 서버 배치

| 파일 | 역할 |
|---|---|
| `domain/insight/controller/InsightController.java` | 두 GET, 파라미터 검증 |
| `domain/insight/service/InsightService.java` | 기간 루프, `SleepService.mergedNight`·`CaffeineRecordRepository`·`UserSettingsService` 조합 |
| `domain/insight/dto/*` | §3.2 |
| `domain/calc/insight/InsightCalc.java` | 순수 계산: 그룹 나누기, 평균, 마감 판정, 표본 기준, 차이 판정. `Clock`·DB에 의존하지 않음 |
| 테스트 | `calc/insight/InsightCalcTest`(골든 값), `insight/InsightControllerIT`(기간 검증·사용자 격리·표본 부족) |

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
- 진입 위치: 탭을 하나 추가할지, 수면 분석 탭 상단에 "카페인과 수면" 카드를 두고 push할지 정한다. → §7 기타 A
- 빈 상태·오류 상태는 홈의 `CutoffUnavailableHero` 패턴을 따른다(`../../captest1/captest1/Features/Caffeine/Views/HomeView.swift`). 실패를 "차이 없음"처럼 보이게 하지 않는다.
- 단위 테스트: `captest1Tests/InsightStoreTests.swift`. 기존 `Fakes/`의 페이크 Repository 패턴을 쓴다.
- 차트는 기존 `CaffeineChartView`·`CalibrationScatterChart`와 같이 Swift Charts로 그리고, 색은 `Theme` 토큰만 쓴다.

---

## 5. 선행 작업

| # | 작업 | 지금 | 리포트에 필요한 이유 | 범위 |
|---|---|---|---|---|
| 1 | **학습 자동 실행** | 학습 탭을 열 때만 `/api/learning/run`을 호출한다(`../../captest1/captest1/Features/Learning/Views/LearningDashboardView.swift:55`의 `.task`) | 리포트 잔량은 유효 반감기로 계산한다(§2.2). 학습이 안 돌면 그 반감기가 갱신되지 않아 잔량 값이 낡는다. 학습 탭과 리포트가 서로 다른 반감기를 말하게 된다 | 앱: `App/ContentView.swift:51` `.task`에서 `sleepStore.loadDataIfNeeded()` 직후 학습 실행 호출로 이동 |
| 2 | 수면 업로드 기간 | 앱은 실행할 때마다 최근 **7일**만 업로드한다(`AppConstants.Sleep.recentHistoryDays = 7`, `../../captest1/captest1/Core/Model/AppConstants.swift:17`, `SleepStore.swift:41`) | 서버에 이미 올라간 샘플은 남는다. 그래서 주 1회 이상 앱을 연 사용자는 30일이 쌓이지만, 첫 사용자는 14·30일 리포트가 7일치뿐이다 | 앱: 최초 1회 30일 백필 또는 기간 확대. 증분 조회(로드맵 §2 (17))와 같이 검토 |
| 3 | ~~서버 문서 브랜치 정리~~ (해소) | main `docs/features.md` "예정" 표에 두 기능이 같은 이름으로 들어 있다(2026-11-13 확인) | — | — |

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

★ 표시는 도메인 계산식이나 상수에 걸리는 항목이다. 모두 **추천안 · 사용자 결정 대기**이며 확정이 아니다. 결정 전에는 `calc` 상수나 코드를 만들지 않는다.
나머지(6~12)는 계산식을 바꾸지 않는 응답 형태·검증 규칙이다. 추천안대로 §2·§3에 반영해 두었고, 구현 PR 리뷰에서 바꿀 수 있다.
코드 경로는 `src/main/java/com/jongbeom/server/domain/` 기준이다.

### ★ 결정 대기

| # | 항목 | 추천안 (결정 대기) | 근거 | 대안과 트레이드오프 |
|---|---|---|---|---|
| ★1 | 잔량 계산 반감기 | 요청 시점 유효 반감기 하나로 기간 전체를 계산한다 | 홈과 같은 값이다(`caffeine/service/CaffeineService.java:157` `settings.effectiveHalfLifeHours()`). 밤끼리 같은 기준으로 비교된다. 관측 행은 학습된 밤에만 있어(`calc/learning/BayesianHalfLifeUpdater.java:37` 잔량 1mg 미만 skip) 대조군 밤에는 당시 값이 없다 | 밤마다 관측의 `posteriorMean × conditionMultiplier`(`learning/entity/HalfLifeObservation.java:50,56`)를 쓴다. 학습 탭 `predictedResidualAtBedtime`과 일치하지만, 관측 없는 밤은 다른 값으로 메워야 해서 한 리포트 안에 기준이 두 가지 섞인다 |
| ★2 | "차이 있음" 기준값 | SOL 차이 ≥ 5분, 효율 차이 ≥ 3%p. `calc/insight/InsightCalc` 새 상수로 두고 골든 테스트에 포함한다 | SOL 5분은 학습 모델 기울기 `BETA = 0.10`분/mg(`calc/learning/BayesianHalfLifeUpdater.java:25`) × 그룹 경계 50mg(`calc/pk/Pharmacokinetics.java:26`)로, 모델이 예측하는 차이 크기와 같다. 효율 3%p는 대응하는 기존 상수가 없는 제안값이다 | 기준 없이 차이를 항상 서술하면 2~3분 같은 잡음도 "차이"로 말하게 된다. 기준을 더 높이면(예: 10분) 작은 표본에서는 거의 항상 "차이 없음"이 된다 |
| ★3 | 최소 표본 | 리포트 5밤, 그룹당 3밤, SOL 있는 밤 3밤. `InsightCalc`에 자체 상수로 둔다 | 3은 학습 대시보드 R² 최소 관측 수와 같다(`calc/learning/LearningStatsCalc.java:20`). 다만 이 상수는 `private`라서 공유하려면 기존 `calc` 파일을 고쳐야 하므로 값만 맞춘다. 5밤은 7일 프리셋(학습 범위 `learning/service/LearningService.java:50` `RECENT_DAYS = 7`과 같은 길이)에서도 대부분 채워진다 | 더 높이면(7밤·4밤) 수치는 안정되지만 7일 프리셋에서 대부분 "표본 부족"이 된다. `LearningStatsCalc` 상수를 public으로 바꿔 공유하면 값이 한곳에서 관리되지만, 학습 대시보드 기준과 리포트 기준이 함께 움직이게 묶인다 |
| ★4 | 비교 B 기준 섭취량 | 요청 시점 설정 `referenceDoseMg`를 쓴다. 문장에 "지금 기준 용량(Nmg) 기준" 같은 표현을 넣을지도 함께 결정한다 | 홈 마감 시각과 같은 입력이다(`caffeine/service/CaffeineService.java:158`). 설정은 사용자당 한 행이고 이력이 없어 당시 값을 복원할 수 없다. 실제 마지막 잔 용량을 넣으면 `cutoffTime`(`calc/pk/Pharmacokinetics.java:174-192`) 정의상 "마감 지킴 ⇔ 취침 잔량 ≤ 50mg"이 되어 비교 A와 같은 비교가 된다 | 실제 잔 용량: 과거 설정 변경의 영향은 없지만 비교 B가 A의 복사본이 된다. 설정 이력 테이블 추가: 정확하지만 DDL이 바뀌어 이번 기능 범위(§2.7 스키마 무변경)를 넘는다 |
| ★5 | 섭취일과 밤 짝짓기 | 아침 날짜 D의 밤은 섭취일 D−1(로컬 D−1 05:00 ~ D 05:00)과 짝짓는다. 그중 `bedtime` 이전 기록만 쓴다. `dayTotalMg`·`lastIntakeAt`·`cutoffKept`가 이 규칙을 따른다 | 밤 D의 수면 창은 D−1 18:00 ~ D 12:00이다(`calc/time/LocalCalendar.java:51-58`). 섭취일 경계는 05:00이다(`calc/time/LocalCalendar.java:29,37`). 취침이 18:00~05:00 사이인 일반적인 경우 그 밤 직전의 하루가 정확히 섭취일 D−1이 된다 | "취침 전 24시간 창의 마지막 기록"(`calc/learning/BayesianHalfLifeUpdater.java:36` `DOSES_LOOKBACK_HOURS = 24`): 비교 A와 같은 창이고 날짜 경계가 없다. 대신 섭취가 없던 날에 전날 늦게 마신 잔을 "그날 마지막 잔"으로 집어 판정이 흐려진다. 추천안의 약점은 05:00 이후 취침(교대 근무)에서 05:00 ~ 취침 사이 기록이 빠지는 것이다 |

### 응답 형태·검증 (추천안 반영)

| # | 항목 | 추천 |
|---|---|---|
| 6 | `moreNightsNeeded` 의미 | `Comparison`마다 두 그룹의 `max(0, 3 − nightCount)`를 합한다. SOL 부족은 효율 비교만 내도록 할 뿐 `sufficient`를 false로 만들지 않으므로 포함하지 않는다 |
| 7 | 최상위 `sufficient`와 `Comparison` 관계 | 최상위가 false여도 `Comparison`은 null이 아니고 각자 `sufficient = false`, 차이는 null로 내린다. 앱이 null 분기 없이 같은 카드를 그릴 수 있다. 5밤 부족 문장은 `headlines`에 넣는다 |
| 8 | 기간 검증 에러 | `insight/exception/`에 `BusinessException` 상속 예외(예: `InvalidInsightPeriodException`)를 두고 `INVALID_PARAMETER`를 넘긴다. 새 ErrorCode는 만들지 않고, api.md 에러 표 "쓰는 곳"에 기간 검증을 추가한다 |
| 9 | `to = 오늘` | 허용한다. 리포트는 오늘 아침에 끝난 밤까지 포함한다(완결된 밤). 섭취 통계는 오늘을 `days`에 넣되 요약 평균·최대·초과 일수에서는 뺀다 |
| 10 | 마지막 섭취 시각 평균 | 05:00 기준 경과 분으로 평균하고 벽시계 분(0~1439)으로 되돌린다 |
| 11 | 기록 있는 날 0일 | `averageDailyMg`·`maxDailyMg`를 `Double`로 바꾸고 null을 내린다. `averageLastIntakeMinuteOfDay`와 같은 규칙이다 |
| 12 | 문장 언어 | 한국어로 고정하고 locale 파라미터는 두지 않는다. 기존 알림 문구도 서버가 한국어로 만든다(`notification/service/NotificationPlanService.java:107`) |

### 기타 (이 문서 범위 밖, iOS·시연 결정)

- A. **앱 진입 위치**: 5번째 탭으로 추가할지, 수면 분석 탭 안의 카드에서 push할지.
- B. **Watch 없는 시연 환경**: SOL이 전부 null이면 비교는 효율만 남는다. 시드 데이터로 할지, 아침 체크인을 먼저 할지 정해야 한다(로드맵 §6과 같은 질문).

---

## 8. 관련 문서

- iOS 로드맵: `../../captest1/docs/feature-roadmap.md` (§3.1 인사이트, §4 착수 순서, §5 배치 메모)
- 서버 규칙: `../CLAUDE.md`, 문서 색인: `README.md`
- 학습 수식·상수: `../src/main/java/com/jongbeom/server/domain/calc/learning/BayesianHalfLifeUpdater.java`, `../src/main/java/com/jongbeom/server/domain/calc/learning/LearningStatsCalc.java`
