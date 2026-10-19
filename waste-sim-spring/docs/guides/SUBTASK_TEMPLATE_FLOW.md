# 서브태스크 템플릿 — 만들어지는 과정과 채워지는 과정

> 장량동 시뮬레이터(`jangnyang-waste-sim`)를 기준으로, 서브태스크 **템플릿**이 어디서 오는지와
> 요청마다 서브태스크 **인스턴스**가 어떻게 계산되고 채워져 실행까지 가는지를 코드 단위로 적는다.
> 예시 값은 2026-10-19 에 실제로 돌린 시나리오(`scn-76fcfc99` → `scn-41148688`)에서 가져왔다.

## 0. 한눈에

```
 ┌─────────────── 시뮬레이터마다 한 번 (오프라인) ───────────────┐
 │ 시뮬레이터 소스 ──template-generator──▶ 초안 JSON (+근거·검토 필요)   │
 │                                         │ 제공자가 고르고 좁히고 다듬음 │
 │                                         ▼                            │
 │                      ses/jangnyang-templates.json (16개)             │
 └──────────────────────────────────────────────────────────────────────┘
                                │ 기동 시 1회 적재 (TemplateCatalog)
 ┌─────────────── 요청마다 (온라인) ─────────────────────────────┐
 │ get_templates      규칙집을 LLM 에 준다                               │
 │ plan_subtasks      답변 맵 → 인스턴스 16개의 상태(4종)               │
 │   ↺ UNFILLED 를 묻고 답을 더해 다시 계산 (complete 가 될 때까지)      │
 │ validate_answers   원문 답 → 계약 대조 (보정하지 않음)               │
 │ build_scenario     PES + 실험 프레임 → 실행 설정 N벌 → 검증 2겹       │
 │ (사람) 확인 화면    승인 → 토큰 발급 (MCP 도구 아님)                  │
 │ get_scenario_status 상태·토큰 읽기                                    │
 │ run_scenario_by_token 토큰 재대조 → 실행 → 결과                       │
 └──────────────────────────────────────────────────────────────────────┘
```

역할 분담은 한 줄로 요약된다. **해석은 LLM, 판정은 서버.** LLM 은 사용자 말을 답변키로 옮기고
질문을 대화로 풀며, 서버는 무엇을 물어야 하는지 · 답이 계약 안에 있는지 · 돌려도 되는지를 정한다.
조회 경로에 LLM 호출이 없으므로 같은 답변 맵은 언제나 같은 서브태스크 집합을 낸다.

---

## 1. 템플릿이란 무엇인가

템플릿은 **질문이 아니라 규칙**이다([`SubtaskTemplate`](../../simulator/src/main/java/com/wastesim/template/SubtaskTemplate.java)).
"몇 번째로 묻는가" 같은 순서가 없다 — 순서는 요청이 정한다. 한 템플릿이 담는 칸:

| 칸 | 뜻 | 예: `jn.zoneAssignmentRule` |
|---|---|---|
| `templateId` | 템플릿 식별자 | `jn.zoneAssignmentRule` |
| `answerKey` | LLM·사용자가 쓰는 답변 이름 | `zoneAssignmentRule` |
| `configField` | 실행 설정(`SimulationConfig`)의 필드. 평탄화·역검증이 이 이름으로 세터·게터를 찾는다 | `zoneAssignmentRule` |
| `valueType` | `ENUM` / `ENUM_LIST` / `INTEGER` / `INTEGER_LIST` / `NUMBER` / `STRING` | `ENUM` |
| `allowed` | 닫힌 선택지 (수치형이면 비어 있음) | `[CONTIGUOUS, ROUND_ROBIN]` |
| `min` · `max` · `unit` | 수치 범위와 단위 | — |
| `defaultValue` · `defaultBasis` | 제안할 기본값과 그 근거. **`null` 이면 기본값 없이 반드시 묻는다** | `null` |
| `generateWhen` | **이 결정이 언제 존재하는가** — 서브태스크의 존재를 정한다 | `ZONE_PROXY_OVER_4_BUILDINGS` |
| `question` | LLM 이 물을 문장 초안 | "건물을 교통 구역에 어떻게 배정할까요? …" |
| `sesPath` · `nodeKind` | SES(시스템 엔티티 구조) 상의 위치와 노드 종류 | `경로교통/구역배정` · `ATTRIBUTE` |

`answerKey` 와 `configField` 가 다를 수 있다는 점이 중요하다. 예컨대 `truckCount` 는 `numTrucks`
로, `trafficMode`(APPLY/IGNORE)는 불리언 `trafficEnabled` 로 간다. 답변키는 제공자가 지은 이름이고
설정 필드는 코드의 이름이다.

장량동 템플릿 16개와 생성 조건:

| answerKey | 생성 조건 | 기본값 |
|---|---|---|
| truckType | ALWAYS | LARGE_5TON |
| truckCount | ALWAYS | 1 |
| dispatchIntervalMinutes | EFFECTIVE_TRUCKS_AT_LEAST_2 | 0 |
| collectionTimeMinutes | SINGLE_COLLECTION_TIME | 720 |
| collectionTimesMinutes | MULTI_COLLECTION_TIMES_GIVEN | 없음 |
| trafficMode | ALWAYS | IGNORE |
| trafficProfileId | TRAFFIC_APPLIED | jangryang-weekday |
| travelTimeMode | ALWAYS | LEGACY_CONSTANT |
| zoneAssignmentRule | ZONE_PROXY_OVER_4_BUILDINGS | **없음** |
| intraZoneTravelMinutes | CONTIGUOUS_ZONE_RULE | **없음** |
| numBuildings | ALWAYS | 4 |
| residentsPerBuilding | ALWAYS | 25 |
| occupationMix | ALWAYS | [BlueCollar, Student, Housewife] |
| days | ALWAYS | 30 |
| seeds | ALWAYS | 30 |
| routeAvailableCapacityKg | ALWAYS | **없음** |

---

## 2. 템플릿이 만들어지는 과정 (시뮬레이터마다 한 번)

### 2.1 정본은 손으로 쓴 JSON 이다

서버가 읽는 것은 [`ses/jangnyang-templates.json`](../../simulator/src/main/resources/ses/jangnyang-templates.json)
하나다. [`TemplateCatalog`](../../simulator/src/main/java/com/wastesim/template/TemplateCatalog.java) 가
**기동 시 한 번** 읽어 메모리에 고정한다.

- 실행 중에 다시 읽지 않는다 — 같은 요청을 두 번 돌렸는데 템플릿이 달라지면 재현성이 깨진다.
- 리소스가 없거나 `sesId` · `sesVersion` 이 비어 있으면 **기동을 실패**시킨다. 빈 식별자가 실리면
  "어느 SES 로 만든 설정인가" 를 되물을 수 없는 시나리오가 확인 화면과 실행까지 흘러간다.
- `generateWhen` 은 JSON 에서 문자열이지만 읽는 순간 [`GenerateCondition`](../../simulator/src/main/java/com/wastesim/template/GenerateCondition.java)
  enum 상수로 바뀐다. 모르는 이름이면 역직렬화가 실패한다.

### 2.2 초안은 코드에서 뽑는다 — `template-generator`

제공자가 시뮬레이터 코드를 읽고 위 JSON 을 손으로 쓰던 일을 줄이려고 만든 모듈이다
([기록](../reports/template-generator-2026-10-05/REPORT.md)). 시뮬레이터를 컴파일 의존성으로 두지 않고
**JavaParser 로 소스만** 읽는다 — 다른 시뮬레이터에도 같은 방식으로 붙이기 위해서다.

```powershell
.\mvnw.cmd -q -pl template-generator -am install -DskipTests
.\mvnw.cmd -q -pl template-generator exec:java
```

입력은 넷이다(`Main` 의 인자).

| 인자 | 무엇 | 장량동에서 |
|---|---|---|
| `--config` | 설정 클래스 | `SimulationConfig` |
| `--validator` | 범위를 검증하는 클래스들 | `SimulationConfigValidator` 등 |
| `--engine` | 엔진 클래스들. 첫 클래스의 public 메서드가 진입점 (선택) | `SimulationEngine` … |
| `--compare` · `--conditions` | 대조할 손으로 쓴 템플릿과 조건 사전 (선택) | 위 JSON · [`jangnyang-generate-conditions.json`](../../template-generator/conditions/jangnyang-generate-conditions.json) |

#### 칸별 추출 규칙 ([`TemplateGenerator`](../../template-generator/src/main/java/com/wastesim/templategen/TemplateGenerator.java))

| 템플릿 칸 | 읽는 곳 | 규칙 |
|---|---|---|
| 후보 | 설정 클래스 | **세터가 있는 인스턴스 필드** = 사용자가 정할 수 있는 값 |
| `valueType` | 필드 타입 | `int`→INTEGER, `double`→NUMBER, `List<Integer>`→INTEGER_LIST, enum 으로 해석되는 `String`→ENUM |
| `allowed` | enum 선언 | 필드가 `X.fromName(필드)` / `X.valueOf(필드)` 로 해석되면 X 의 상수 전부 |
| `defaultValue` | 필드 초기값 | 리터럴, 사칙연산(`20 * 60`), `Enum.X.name()` → `"X"` |
| ↳ 초기값이 `null` | 설정 클래스의 대체값 ([`FallbackFinder`](../../template-generator/src/main/java/com/wastesim/templategen/FallbackFinder.java)) | `if (f == null) return X;`, `f == null ? X : …` 등에서 X 가 상수로 풀리면 그것. 다른 필드로 대신하면("건물 수만큼") 기본값으로 채우지 않는다 |
| `min` / `max` | 검증기 | **then 쪽이 바로 오류를 만드는 `if`** 의 "필드 비교 상수". `x < 1` → 하한 1, `x > 365` → 상한 365, `x <= 0` → 0 초과 |
| `unit` | 필드 이름 끝말 | `…Minutes` → minute, `…Kg` → kg |
| `question` | Javadoc | 첫 문장 그대로 (문장을 짓지 않는다) |
| `generateWhen` | 엔진 코드 ([`ConditionExtractor`](../../template-generator/src/main/java/com/wastesim/templategen/ConditionExtractor.java)) | 아래 2.3 |

모든 값에 근거(`파일:줄 식`)를 `_extraction.evidence` 로 남기고, 코드만으로 정하지 못한 칸은
`_extraction.needsReview` 에 이유를 적는다. **추측으로 채우지 않는다.** 예를 들어
`routeAvailableCapacityKg` 의 상한은 `allocated > truckType.capacityKg` — 차종에 따라 달라서 상수로
풀리지 않으므로 비워 두고 식을 넘긴다. 실제 템플릿에서도 이 칸은 `min`/`max` 가 비어 있고, 범위의
정본은 `SimulationConfigValidator` 로 남는다.

오탐을 막는 규칙 셋:
- 게터는 **설정 타입 변수**에서 부를 때만 그 필드로 본다 (다른 객체의 `getThreshold()` 를 섞지 않게).
- 게터는 이름이 아니라 **본문**을 따른다 — `getTruckCount() { return numTrucks; }` 는 `numTrucks` 다.
- 바깥 `if` 는 범위가 아니라 "이 검사를 할지" 인 경우가 많으므로, then 이 **바로** 오류를 만드는 `if` 만 센다.

### 2.3 생성 조건 뽑기

질문: "이 설정 필드는 **어떤 설정일 때** 결과에 쓰이는가?"

1. 진입점(첫 엔진 클래스의 public 메서드)에서 닿는 메서드만 훑는다.
2. 필드를 읽는 자리마다 그 자리를 감싸는 조건을 모은다 — 감싸는 `if`/`else`, 삼항식, `&&`·`||` 의
   앞쪽, 앞선 문장의 조기 탈출(`if (c) return;`).
3. 메서드 사이는 "그 메서드를 부르는 자리들의 조건" 을 **또는** 으로 잇는다.
4. 필드의 생성 조건 = 모든 읽는 자리의 조건을 **또는** 으로 이은 것.

설정만으로 정해지지 않는 조건(경로가 비었는가, 같은 구역인가 …)은 [`Cond.Runtime`](../../template-generator/src/main/java/com/wastesim/templategen/Cond.java)
으로 남기고 **"참일 수 있다"** 로 읽는다. 그래서 이 추출은 **필요조건**이다 — 물을 필요가 없을 때
물을 수는 있어도, 물어야 할 때 빠뜨리지는 않는 쪽으로 기운다.

결과 객체에 값을 **그대로 기록만** 하는 읽기(`new SimulationResult(cfg.getCollectionTimeLabel(), …)`)는
조건에서 뺀다. 동작이 아니라 보고이기 때문이다. 이걸 빼기 전에는 단일 수거 시각이 `ALWAYS` 로 나왔다.

따라가지 않는 것: `switch`, 반복문 조건, 예외 흐름, 리플렉션. 그리고 **대화 규칙**("사용자가 말했을 때만
묻는다")은 코드에 없으므로 나오지 않는다.

### 2.4 대조 — 초안은 얼마나 맞는가

[`TemplateComparator`](../../template-generator/src/main/java/com/wastesim/templategen/TemplateComparator.java) 가
`configField` 로 짝을 맞춰(답변키는 코드에 없지만 설정 필드는 양쪽에 같다) 6칸(값 종류 · 선택지 · 기본값 ·
하한 · 상한 · 단위)을 채점한다. 생성 조건은 [`Truth`](../../template-generator/src/main/java/com/wastesim/templategen/Truth.java)
가 **진리표**로 맞댄다 — 불리언은 참/거짓, enum 은 상수 전부와 null, 목록은 null/빈/채움, 수는 조건에 나온
경계값의 앞뒤를 격자로 깔고, **검증기에서 뽑은 범위 안**에서만 비교한다(건물 0동 같은 불가능한 설정은 빼야
엉뚱한 조건이 안 나온다).

| | 결과 |
|---|---|
| 코드에서 나온 후보 | 40개 (옮기지 못한 필드 2개) |
| 손으로 쓴 템플릿과 짝 | 16 / 16 |
| 6칸 일치 (손으로 쓴 값이 있는 칸만) | **47 / 54 (87%)** |
| 생성 조건 같음 / 더 넓음 / **더 좁음** | 12 / 4 / **0** |

더 넓게 나온 4개:

| 템플릿 | 손으로 쓴 조건 | 뽑은 것 | 왜 다른가 |
|---|---|---|---|
| 배차 간격 | 실제 운행 2대 이상 | ALWAYS | `k * 간격` 이 `routes.size()` 에 달렸고, 그것이 `min(건물, 차량)` 임은 `RoutePlanner` 계산을 풀어야 안다 |
| 하루 수거 시각 목록 | 사용자가 말했을 때 | ALWAYS | 대화 규칙이라 코드에 없다 |
| 구역 배정 규칙 | 구역 근사 · 5동 이상 | 더 넓음 | 엔진은 교통 반영이면 상수 모드에서도 구역별 혼잡도를 찾는다. 손으로 쓴 쪽은 "안 정하면 막히는가", 뽑은 쪽은 "결과에 들어가는가" 가 기준 |
| 구역 내 이동시간 | 구역 근사 · 5동 이상 · 덩어리 | 구역 근사일 때 | 나머지 조건이 실행 조건 `fromZone.equals(toZone)` 에 숨어 있다 |

더 좁은 쪽으로 틀린 적이 없다는 것이 요점이다. 초안을 그대로 써도 **실험이 틀리지는 않고 질문만 몇 개
늘어난다**(장량동에서 최대 4개).

### 2.5 제공자에게 남는 일

1. 후보 40개 중 **무엇을 물을지** 고르기 (장량동은 16개)
2. 넓게 나온 생성 조건을 **좁히고 이름 붙이기** — 이름 붙인 조건은 `GenerateCondition` 에 상수로 구현한다
3. 선택지를 좁히고 **질문 문장** 다듬기, 기본값의 근거(`defaultBasis`) 적기

생성 조건을 자유 식이 아니라 **명명된 enum 상수**로 두는 이유: 식 파서를 만들면 제공자가 무엇이든 쓸 수
있지만, 그 식이 실제로 무엇을 읽는지 시험할 자리가 사라진다. 상수 하나하나가 단위 테스트 대상이다.

---

## 3. 서브태스크가 채워지는 과정 (요청마다)

### 3.1 시작: 브로커가 서버를 정한 뒤

`find_simulators` 가 `MATCH` 로 장량동 서버를 추천하면, 그 대화에서는 브로커를 다시 부르지 않고
`get_templates` 부터 시뮬레이터 MCP 만 쓴다. 브로커에 넘긴 요청 구절("학생이 많음", "평일 교통량" …)이
LLM 이 첫 답변 맵을 만드는 재료가 된다.

`get_templates` 는 16개 규칙과 `sesId`/`sesVersion` 을 그대로 준다. LLM 은 이 규칙의 `allowed` ·
`question` · `defaultValue` 를 보고 사용자 말을 답변키로 옮긴다.

> 예: "학생이랑 주부가 많은 동네, 평일 교통량 반영" →
> `{"occupationMix":["Student","Housewife"], "trafficMode":"APPLY", "trafficProfileId":"jangryang-weekday"}`

### 3.2 인스턴스 계산 — `plan_subtasks`

[`SubtaskPlanner.plan(answers)`](../../simulator/src/main/java/com/wastesim/template/SubtaskPlanner.java)는
**상태가 없는 순수 계산**이다. 답변 맵 하나를 받아 템플릿 16개를 차례로 돌며:

```java
Activation a = t.generateWhen().evaluate(answers);   // ACTIVE / INACTIVE / UNKNOWN
status = switch (a) {
    case INACTIVE -> NOT_GENERATED;
    case UNKNOWN  -> DEFERRED;
    case ACTIVE   -> answers.containsKey(t.answerKey()) ? FILLED : UNFILLED;
};
```

답이 바뀌면 다시 부르면 되고, 결과가 이전과 달라지는 것이 정상이다. 서버는 대화 이력을 들고 있지 않다.

#### 판정이 왜 세 갈래인가

[`Activation`](../../simulator/src/main/java/com/wastesim/template/Activation.java) 은 참·거짓이 아니라
셋이다. 조건이 의존하는 값이 아직 없을 때 거짓을 돌려주면 **"아직 모른다"와 "필요 없다"가 같은 답**이
된다. 그러면 아무 답도 하지 않은 대화가 모든 조건부 가지를 건너뛰고 완결로 통과한다.

| 상태 | 뜻 | 묻는가 |
|---|---|---|
| `FILLED` | 생성됐고 값이 있다 | 아니오 |
| `UNFILLED` | 생성됐는데 값이 없다 | **예** (`toAsk()`) |
| `DEFERRED` | 판정에 필요한 답이 아직 없다 — **보류** | 아직 아니오. 의존하는 답이 오면 다시 본다 |
| `NOT_GENERATED` | 필요 없다고 **판정했다** | 아니오 |

`complete` = `UNFILLED` 도 `DEFERRED` 도 없음. 이때만 PES 를 만들 수 있다 — `build_scenario` 가 실행 설정마다
같은 계산을 다시 해 확인한다(3.4).

#### 생성 조건 7종의 판정 규칙

| 조건 | ACTIVE | INACTIVE | UNKNOWN |
|---|---|---|---|
| `ALWAYS` | 항상 | — | — |
| `EFFECTIVE_TRUCKS_AT_LEAST_2` | `min(numBuildings, truckCount) ≥ 2` | 그 외 | 둘 중 하나라도 없음 |
| `TRAFFIC_APPLIED` | `trafficMode == APPLY` | 다른 값 | `trafficMode` 없음 |
| `ZONE_PROXY_OVER_4_BUILDINGS` | `travelTimeMode == ZONE_PROXY_HYBRID` 이고 `numBuildings > 4` | 그 외 | 둘 중 하나라도 없음 |
| `CONTIGUOUS_ZONE_RULE` | 위가 ACTIVE 이고 `zoneAssignmentRule == CONTIGUOUS` | 위가 INACTIVE 이거나 규칙이 ROUND_ROBIN | 규칙 없음 |
| `MULTI_COLLECTION_TIMES_GIVEN` | `collectionTimesMinutes` 가 비지 않음 | 없음 | — (보류 없음) |
| `SINGLE_COLLECTION_TIME` | 목록이 없음 | 목록이 있음 | — |

조건마다 설계 이유가 있다.

- **실제 운행 대수** — 건물보다 차량이 많으면 엔진이 빈 경로의 운행을 만들지 않는다. 1대면 차량 간
  시간차가 없어 배차 간격이 결과를 바꾸지 못한다.
- **5동 이상** — 4동까지는 지점 id 를 구역 id 로 보는 폴백이 이름이 겹쳐 통한다(`Node_A~D`). 5동부터는
  `Node_E` 라는 구역이 없어 막히므로 배정 규칙을 정해야 한다.
- **덩어리일 때만 구역 안 이동시간** — 구역 간 행렬에는 대각 성분이 없다(구역은 점이 아니라 영역이다).
  번갈아 배정하면 모든 구간이 구역을 넘으므로 필요 없다.
- **연쇄 INACTIVE** — 구역 배정 자체가 생성되지 않는 설정이면 구역 안 이동시간도 `INACTIVE` 다. 이때
  규칙 값이 없는 것은 "아직 모름" 이 아니라 "영영 없음" 이다. 보류로 두면 필요한 값을 다 채워도 완결이
  되지 않는다.
- **다회 수거는 서버가 먼저 묻지 않는다** — 기본은 하루 1회이고, 다회를 말하지 않은 대화에 목록을 물으면
  흐름이 한 칸 늘어난다. 그래서 보류가 없다.
- **단일 시각과 목록은 배타** — 둘 다 받으면 엔진은 목록을 쓰고 단일 시각을 조용히 버린다. 그래서 목록이
  있으면 단일 시각을 아예 만들지 않는다.

#### 실제 흐름 — 이 세션에서 상태가 바뀐 순서

| 회차 | 새로 들어온 답 | `zoneAssignmentRule` | `intraZoneTravelMinutes` | `dispatchInterval` | complete |
|---|---|---|---|---|---|
| 1 | 교통 APPLY, 학생·주부, 1대 | DEFERRED (`travelTimeMode`·`numBuildings` 없음) | DEFERRED | DEFERRED (`numBuildings` 없음) | ✗ (UNFILLED 8) |
| 2 | 4동, 구간 상수 … 전부 | NOT_GENERATED | NOT_GENERATED (연쇄) | NOT_GENERATED (min(4,1)=1) | ✓ |
| 3 | 이동시간 → 교통구역 근사 | NOT_GENERATED (4동) | NOT_GENERATED | NOT_GENERATED | ✓ |
| 4 | 26동 | **UNFILLED** → 물음 | DEFERRED (규칙 모름) | NOT_GENERATED | ✗ |
| 5 | ROUND_ROBIN | FILLED | NOT_GENERATED | NOT_GENERATED | ✓ |
| 5′ | CONTIGUOUS | FILLED | **UNFILLED** → "2분" | NOT_GENERATED | ✗ → ✓ |

회차 4 처럼 **답 하나가 새 질문을 낳고**, 회차 5 처럼 **답 하나가 보류를 없앤다**. 이 연쇄를 LLM 이
기억해서 판단하지 않고 서버가 매번 다시 계산하는 것이 `plan_subtasks` 의 존재 이유다. 도구 설명대로,
이 자리는 LLM 이 스스로 판정한 결과를 **서버가 대조**하는 곳이기도 하다 — 둘이 다르면 LLM 이 생성
조건을 잘못 읽었다는 뜻이고, 그 차이 자체가 측정 대상(RQ2)이다.

#### UNFILLED 를 채우는 방법 — 기본값이 있느냐 없느냐

- `defaultValue` 가 있으면 LLM 이 기본값을 제안해 채울 수 있다. 이때 출처는 `MODEL_DEFAULT` 가 되고,
  나중에 확인 화면에서 **승인하지 않은 기본값**으로 드러난다(3.4).
- `defaultValue` 가 `null` 이면(`requiresExplicitAnswer()`) 기본값 없이 **반드시 사용자에게 묻는다**.
  장량동에서는 `routeAvailableCapacityKg`, `zoneAssignmentRule`, `intraZoneTravelMinutes`,
  `collectionTimesMinutes` 넷이다. 이 세션에서 적재 몫(100kg → 650kg), 배정 방식, 구역 안 2분을
  따로 물은 것이 이 때문이다.

### 3.3 값 검증 — `validate_answers`

[`AnswerNormalizer`](../../simulator/src/main/java/com/wastesim/template/AnswerNormalizer.java)가 원문 문자열을
템플릿 계약에 대조해 정규화한다.

| valueType | 받는 꼴 | 거절 사유 |
|---|---|---|
| ENUM / allowed 있음 | 대소문자 무시하고 허용값과 일치 | `OUT_OF_CLOSURE` |
| ENUM_LIST | `["Student","Housewife"]` 또는 `Student, Housewife`. **중복은 비중**이라 지우지 않음 | `OUT_OF_CLOSURE`, `EMPTY_ANSWER` |
| INTEGER | 정수 + min/max | `NOT_A_NUMBER`, `OUT_OF_RANGE` |
| INTEGER_LIST | `[660,1380]` 또는 쉼표. 순서 유지, **중복은 거절** | `DUPLICATE_VALUE` 외 |
| NUMBER | 유한한 실수 + min/max | `NOT_A_NUMBER`, `OUT_OF_RANGE` |
| (빈 답) | — | `EMPTY_ANSWER` |

두 원칙:

- **보정하지 않는다.** 맞지 않으면 사유 코드와 허용값만 돌려준다. 가까운 값으로 바꾸면 사용자가 요청한
  것과 다른 실험이 돌고, 그 사실이 어디에도 남지 않는다.
- **자연어를 해석하지 않는다.** "1톤" → `SMALL_1TON` 은 LLM 의 일이다. 서버까지 자연어를 해석하기 시작하면
  틀렸을 때 LLM 과 서버 중 어느 쪽이 틀렸는지 가릴 수 없다.

`NUMBER` 의 범위가 비어 있는 경우(적재 몫)는 상한이 다른 답(차종)에 달려 있어서다. 범위를 두 곳에서
정의하지 않으려고 `SimulationConfigValidator` 를 정본으로 남긴다 — 그래서 그 검사는 다음 단계에서 걸린다.

### 3.4 시나리오 조립 — `build_scenario`

입력은 셋이다: 확정값(`values`), 값마다의 출처(`origins`), 실험 프레임(`variableAnswerKey` + `variableValues`).

#### (1) PES 만들기

[`Pes`](../../simulator/src/main/java/com/wastesim/pes/Pes.java)(Pruned Entity Structure)는 SES 의 열린 선택
지점이 모두 하나로 닫힌 상태다. 생성자가 막는 것:

- `sesId`/`sesVersion` 이 비어 있으면 거절 — 템플릿 카탈로그에서 가져오며, 도구 코드에 적지 않는다.
- **출처 없는 값은 거절** — 출처가 없으면 승인 여부를 판정할 수 없다. (도구는 출처를 안 준 키를
  `MODEL_DEFAULT` 로 채워 넘긴다 — 사용자 값으로 넘겨짚으면 확인 화면 경고에서 빠진다. 2026-10-19 변경.)
- `unapprovedDefaults()` = 출처가 `MODEL_DEFAULT` 인 값들. 이 세션 마지막 시나리오에서는
  `truckType · truckCount · residentsPerBuilding` 셋이었다.

#### (2) 실험 프레임으로 N벌 펼치기

[`ExperimentFrame`](../../simulator/src/main/java/com/wastesim/pes/ExperimentFrame.java)은 PES 에서
**마지막으로 열려 있는 자리**를 N개 값으로 닫는다. 모델 결정(몇 동, 어떤 차)과 실험 결정(무엇을 바꿔
비교할지)을 나눠 두는 이유가 여기 있다.

[`ScenarioBuilder.build`](../../simulator/src/main/java/com/wastesim/pes/ScenarioBuilder.java)는 값마다:

1. PES 값에 실험 변수 하나를 끼워 넣는다 (`collectionTimeMinutes = 540`, `720`, …).
2. **평탄화** — [`PesFlattener`](../../simulator/src/main/java/com/wastesim/pes/PesFlattener.java)가 각 답변키의
   템플릿 `configField` 로 세터를 찾아 `SimulationConfig` 에 넣는다. 이름 규약이 아니라 **선언된 대응**이다.
   - 템플릿에 없는 답변키 → 예외. 조용히 무시하면 사용자가 준 값이 실행에 반영되지 않은 채 넘어간다.
   - 유일한 변환: `trafficMode` APPLY/IGNORE → `trafficEnabled` true/false.
   - JSON 정수 `650` → `Double` 세터용으로 맞춤, 목록 원소는 정수/허용값인지 다시 확인.
3. **설정 검증** — `SimulationConfigValidator.validate(cfg)`. 실패 사유가 `blocks` 에 쌓인다.
4. **역검증** — [`PesBackVerifier`](../../simulator/src/main/java/com/wastesim/pes/PesBackVerifier.java)가 만든 설정을
   게터로 **되읽어** PES 와 대조한다. 읽지 못한 것도 통과가 아니라 불일치로 센다. 이게 없으면
   "사용자가 확인한 설정으로 실행했다" 를 주장할 근거가 없다.

`valid` = `blocks` 와 `backVerificationBlocks` 가 모두 비었음. `scenarioId` 는
`hash(PES 값, 실험 변수 키, 비교 값들)` — 실험 프레임까지 넣어야 같은 PES 에 조건만 다른 시나리오 둘이
서로를 덮지 않는다. 유효한 시나리오만 [`ScenarioStore`](../../simulator/src/main/java/com/wastesim/mcp/ses/ScenarioStore.java)에
보관된다(메모리에만 — 서버가 다시 뜨면 토큰도 함께 사라지는 것이 맞다).

#### 서브태스크 완결을 서버가 다시 센다 (2026-10-19)

`ScenarioBuilder` 는 실행 설정마다 — PES 에 그 실행의 실험 변수 값을 넣은 답변으로 — 생성 조건을 다시 평가한다.
`UNFILLED` 나 `DEFERRED` 가 남으면 그 실행을 막고 사유에 답변키와 질문을 적는다.

- 예전에는 `plan_subtasks` 의 `complete` 를 LLM 이 지켜 줘야 했다. 실험(R10)에서 장량동의 기본값 없는 값
  (`routeAvailableCapacityKg`)이 빠진 채 통과해 실행 설정 초기값으로 돌 뻔했다.
- 기본값이 있는 결정도 `values` 에 넣어야 한다 — 사용자가 받아들였으면 `MODEL_DEFAULT` 로.
- 실행 설정마다 세므로 실험 변수에 따라 생기는 결정이 갈린다. 차량 1대 · 3대를 비교하면 3대 실행에만
  배차 간격이 필요하고, 빠졌으면 3대 실행만 막힌다.

#### 이 세션에서 걸린 검증 — 템플릿이 못 막는 것

26동 · 적재 몫 100kg 시나리오(`scn-a6c41c7d`)는 `validate_answers` 를 통과했지만 여기서 막혔다.

```
collectionTimeMinutes=540: 쓰레기 적재량이 실제 신규 수거 가능량을 초과(예측 적재율 585%)하여 요청을 수행할 수 없습니다.
```

`SimulationConfigValidator.predictOverflowRatio` 가 `일 배출량(건물 × 세대 × 평균 배출) ÷ (운행 차량 ×
운행별 수거 가능량 × 일 수거 횟수)` 로 근사한 값이 1.2 를 넘으면 막는다. 값 하나하나는 계약 안에 있어도
**값들의 조합**이 물리적으로 불가능한 경우다. 템플릿의 `min`/`max` 는 칸 하나만 보므로 이것을 표현할 수
없고, 그래서 범위의 정본은 검증기다.

### 3.5 사람의 확인 — 토큰 발급

검증 통과는 **"돌릴 수 있다"** 이고 확인은 **"돌려도 된다"** 다. 둘을 한 단계로 합치면 "사용자가 확인하지
않은 상태" 를 표현할 자리가 없어진다.

- `build_scenario` 는 토큰을 주지 않는다. 상태는 `UNCONFIRMED`.
- 사람이 확인 화면(`localhost:8090/confirm.html`)에서 PES 값 · 출처 · **승인하지 않은 기본값** · 실행 설정
  N벌을 보고 승인하면, `ScenarioConfirmController` → [`ConfirmScenarioTool`](../../simulator/src/main/java/com/wastesim/mcp/ses/ConfirmScenarioTool.java)이
  토큰을 발급한다.
- **`ConfirmScenarioTool` 은 MCP 도구가 아니다.** 도구로 내면 모델이 스스로 불러 동의를 만들어낼 수 있고,
  그러면 토큰이 "사람이 보고 동의했다" 를 뜻하지 못한다. 모델은 `get_scenario_status` 로 **읽기만** 한다.
- 확인 화면은 서버가 루프백이 아닌 주소에 묶여 있으면 경고를 띄운다 — 인증이 없으므로 같은 네트워크의
  누구나 확인을 눌러 줄 수 있기 때문이다.
- 토큰 = `"cft-" + SHA-256(실행 설정 N벌의 모든 게터 값을 이름순으로 이은 문자열)` 앞 8바이트.

상태는 별도 필드가 아니라 **토큰과 실행 시각**으로만 가른다 — 둘이 어긋날 여지를 없애기 위해서다.

| 토큰 | 실행 시각 | 상태 |
|---|---|---|
| 없음 | — | `UNCONFIRMED` |
| 있음 | 없음 | `CONFIRMED` |
| — | 있음 | `EXECUTED` |

### 3.6 실행 — `run_scenario_by_token`

[`RunScenarioByTokenTool`](../../simulator/src/main/java/com/wastesim/mcp/ses/RunScenarioByTokenTool.java)은 실행 직전에
다시 센다.

1. 토큰이 없으면 거절 — 확인하지 않은 설정은 실행하지 않는다.
2. 이미 `EXECUTED` 면 거절 — **확인 한 번에 실행 한 번**. 토큰이 계속 통하면 확인이 무기한 허가가 된다.
3. 보관된 실행 설정을 **다시 해싱**해 토큰과 대조 — 발급 이후 설정이 바뀌었으면 어긋난다.
4. 실행 설정마다 `runExperiment(cfg)` 로 시드 `1..seeds` 를 돌려 평균을 낸다. 모든 설정이 같은 시드를
   쓰므로 설정 간 비교를 **같은 시드끼리 짝지어** 할 수 있다(이 세션의 12시 대 19시 대응 비교가 이것).
5. 결과에 `notForOperationalUse: true` 와 `dataQualityFlags` · `assumptionNotes` 를 함께 싣는다
   (예: `ZONE_ASSIGNMENT_ASSUMED`, `INTRA_ZONE_TIME_ASSUMED`). 구간 상수 모드에서 이동시간이 0 이면
   운행 소요 시간을 `0` 이 아니라 `null` + 이유로 낸다 — "0분 걸렸다" 가 아니라 "재지 않았다" 이기 때문이다.

---

## 4. 출처(origin)가 흐르는 길

| 단계 | 출처를 아는가 |
|---|---|
| `plan_subtasks` | 선택 입력 `origins` 를 주면 그대로 싣고, 주지 않으면 비워 둔다(예전에는 항상 `USER`) |
| `validate_answers` | 관심 없음 (계약만 본다) |
| `build_scenario` | `origins` 로 받는다. 안 준 키는 `MODEL_DEFAULT`, 모르는 출처는 거절, 실험 변수는 프레임이 정한 것(`USER`)으로 본다 |
| 확인 화면 | `MODEL_DEFAULT` 를 "사용자가 승인하지 않은 기본값" 으로 따로 띄운다 |
| 실행 결과 | 출처는 싣지 않는다. 대신 가정 플래그를 싣는다 |

**출처 규칙**(도구 설명에 적혀 있다, 2026-10-19):

| 출처 | 언제 |
|---|---|
| `USER` | 사용자가 그 값을 직접 말했다 |
| `MODEL_DEFAULT` | 사용자가 값을 말하지 않았다 — LLM 이 기본값을 제안했거나, 사용자가 "기본값으로" · "알아서" 처럼 값 없이 넘겼다 |
| `DERIVED` | 사용자가 말한 다른 내용에서 정해졌다(예: "평일 교통량" → `trafficMode=APPLY`) |

규칙을 적기 전 실험(2026-10-19, 에이전트 12개)에서는 "기본값으로 해줘" 를 `USER` 로 본 에이전트와 `MODEL_DEFAULT`
로 본 에이전트가 갈려, 확인 화면의 미승인 기본값이 0개에서 7개까지 달랐다. 출처의 정직함 자체는 여전히 LLM 이
지켜야 하지만, 규칙이 하나로 정해졌고 출처를 빠뜨리면 경고가 **보이는 쪽**으로 기운다.

## 5. 정리 — 어느 단계가 무엇을 막는가

| 단계 | 막는 것 | 이 세션의 예 |
|---|---|---|
| 템플릿 생성 조건 | 필요한 질문을 빠뜨림 / 쓸모없는 질문 | 4동에서 구역 배정을 묻지 않음, 26동에서 물음 |
| 보류(DEFERRED) | 판단 전에 완결로 넘어감 | 26동 직후 구역 안 이동시간 보류 |
| `validate_answers` | 계약 밖의 값, 몰래 고친 값 | (모두 통과) |
| 설정 검증기 | 값들의 조합이 불가능한 실험 | 26동 · 100kg → 예측 적재율 585% |
| 역검증 | 평탄화 중 값이 사라지거나 바뀜 | (모두 통과) |
| 사람의 확인 | 사용자가 모르는 기본값으로 실행 | 5톤 · 1대 · 25세대 노출 |
| 토큰 재대조 · 1회 실행 | 확인 뒤 바뀐 설정, 확인의 재사용 | 새로 돌릴 때마다 새 시나리오 · 새 승인 |
