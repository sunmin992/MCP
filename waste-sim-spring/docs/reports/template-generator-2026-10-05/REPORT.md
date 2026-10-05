# 서브태스크 템플릿을 시뮬레이터 코드에서 뽑아 손으로 쓴 것과 대조한 기록

2026-10-05 · `feature/simulator-mcp` · 새 모듈 `template-generator` · 전체 테스트 627개 중 624개 통과(스킵 3)

## 왜 만들었나

연구 질문 둘 중 **RQ1 — 시뮬레이션 서비스 제공자가 최소의 노력으로 LLM 기반 시뮬레이션 서비스를
제공한다** 를 재기 위해서다.

이 시스템에서 시뮬레이터마다 새로 써야 하는 것은 서브태스크 템플릿
([`jangnyang-templates.json`](../../../simulator/src/main/resources/ses/jangnyang-templates.json))이다.
서버는 그 파일을 읽어 요청마다 서브태스크를 만들 뿐(5·6단계), 템플릿 자체는 제공자가 시뮬레이터 코드를
읽고 손으로 썼다. 이 손일을 코드에서 얼마나 대신할 수 있는지가 RQ1 의 첫 숫자다.

```
[지금: 제공자가 손으로]         [서버가 요청마다]
 시뮬레이터 코드 ──읽고 씀──▶ 템플릿 JSON ──▶ 서브태스크 생성 ──▶ 질문
        └──── template-generator 가 초안을 쓴다
```

## 무엇을 읽고 무엇을 쓰나

시뮬레이터 클래스를 컴파일 의존성으로 두지 않고 **소스만** 읽는다(JavaParser). 다른 시뮬레이터에도
같은 방식으로 붙이기 위해서다.

| 템플릿 칸 | 읽는 곳 | 규칙 |
|---|---|---|
| 후보 | 설정 클래스 | 세터가 있는 인스턴스 필드 |
| `valueType` | 필드 타입 | `int`→INTEGER · `double`→NUMBER · `List<Integer>`→INTEGER_LIST · enum 으로 해석되는 `String`→ENUM 등 |
| `allowed` | enum 선언 | 필드가 `X.fromName(..)`/`X.valueOf(..)`로 해석되면 X 의 상수 |
| `defaultValue` | 필드 초기값 | 리터럴 · 사칙연산(`20 * 60`) · `Enum.X.name()` |
| `min`/`max` | 검증기 | **오류를 만드는 `if`** 의 "필드 비교 상수". `x < 1`→하한 1, `x <= 0`→0 초과 |
| `unit` | 필드 이름 | `…Minutes`→minute · `…Kg`→kg |
| `question` | Javadoc | 첫 문장(초안) |

모든 값에 뽑아 온 자리(`파일:줄 식`)를 `_extraction.evidence` 로 남기고, 코드만으로 정하지 못한 칸은
`_extraction.needsReview` 에 이유를 적는다. **추측으로 채우지 않는다** — 상수로 풀리지 않는 경계
(`allocated > truckType.capacityKg`)는 비워 두고 식을 넘긴다.

오탐을 막으려고 둔 규칙 셋:

- 게터는 **설정 타입 변수**에서 부를 때만 필드로 본다. 처음엔 분리배출 유형의 `w.getThreshold()` 가
  `threshold` 의 근거로 섞였다.
- 게터는 이름이 아니라 **본문**을 따른다. 검증기가 별칭 `getTruckCount() { return numTrucks; }` 로
  하한을 검사해, 이름 규칙으로는 `numTrucks` 의 하한을 놓쳤다.
- then 쪽이 **바로** 오류를 만드는 `if` 만 센다. 바깥 조건은 범위가 아니라 "이 검사를 할지" 인 경우가 많다.

## 결과 — 장량동 시뮬레이터

[`comparison.md`](comparison.md) · [`generated-templates.json`](generated-templates.json)

| | |
|---|---|
| 코드에서 나온 후보 | 40개 (옮기지 못한 필드 2개: `List<WasteType>`, `double[]`) |
| 손으로 쓴 템플릿 | 16개 — **16개 모두 짝을 찾음** |
| 채점 칸 일치 (6칸 × 16) | **87 / 96 (90.6%)** |
| 손으로 쓴 값이 있는 칸만 | **46 / 54 (85.2%)** |

둘째 줄이 더 정직한 숫자다. 첫째 줄에는 "둘 다 비어 있어서" 맞은 칸(정수 필드의 선택지 등)이 들어 있다.

| 칸 | 값이 있는 칸 중 일치 |
|---|---|
| `min` | 9/9 |
| `max` | 6/6 |
| `unit` | 5/5 |
| `valueType` | 15/16 |
| `defaultValue` | 9/12 |
| `allowed` | 2/6 |

**범위·단위는 검증기에서 전부 나왔다.** 어긋남은 선택지와 기본값에 몰렸다.

### 어긋난 9칸 — 코드에 없는 지식이 무엇인가

어긋남을 "생성기가 틀렸다" 로 뭉개지 않고, 손으로 쓴 템플릿에 **코드 밖의 무엇**이 들어갔는지로 나눴다.
이것이 RQ1 에서 제공자에게 끝까지 남는 몫이다.

| 종류 | 칸 | 무엇 | 자동화 가능성 |
|---|---|---|---|
| **선택지 좁히기** | 2 | `travelTimeMode` 에서 `OSRM_HYBRID`, `zoneAssignmentRule` 에서 `NONE` 을 뺐다 | 낮다 — 제공자의 판단(OSRM 은 좌표 0곳이라 못 쓰고, NONE 은 5동부터 막힌다). 단 검증기가 막는 조합을 따라가면 근거는 찾을 수 있다 |
| **표현 바꾸기** | 3 | 불리언 `trafficEnabled` 를 APPLY/IGNORE 열거로 물었다(값 종류·선택지·기본값 3칸) | 중간 — 선택지 이름만 정하면 된다. 생성기가 "불리언" 검토 항목으로 표시한다 |
| **기본값이 필드 밖에** | 2 | `occupationMix` 는 `resolveOccupationMix()` 의 `baseMix()`, `trafficProfileId` 는 `TrafficDataService.DEFAULT_PROFILE_ID` | 높다 — 해석 메서드의 null 폴백을 따라가면 된다. 다음 판의 대상 |
| **선택지가 데이터 파일에** | 1 | `trafficProfileId` 의 선택지는 교통 프로파일 JSON 파일 이름이다 | 중간 — 코드가 아니라 리소스를 읽어야 한다 |
| **템플릿이 일부러 비움** | 1 | `routeAvailableCapacityKg` 하한. 검증기에는 `<= 0` 거절이 있지만 템플릿은 "범위를 두 곳에서 정의하지 않는다" 며 비웠다 | 해당 없음 — 생성기 값이 코드상 맞다 |

### 채점하지 않은 것

| 칸 | 상태 |
|---|---|
| `generateWhen` | **뽑지 않음.** 16개 중 6개가 조건부(`EFFECTIVE_TRUCKS_AT_LEAST_2` 등)다. 모두 `ALWAYS` 로 두고 검토 대상으로 표시한다. 채점하면 ALWAYS 인 10개가 우연히 맞아 점수가 부풀어 뺐다 |
| `question` | Javadoc 첫 문장을 초안으로 — 40개 중 29개, 손으로 쓴 16개에 해당하는 것 중 11개에 초안이 나왔다. 질문 문장으로 다듬는 일은 남는다 |
| `sesPath` · `nodeKind` | 비움 |
| **노출 선별** | 후보 40개 중 손으로 쓴 템플릿은 16개다. 나머지 24개(`wasteMeanKg`, `threshold`, `landlordEnabled` …)를 사용자에게 물을지는 제공자가 고른다 |

## RQ1 에 대해 이 판이 말하는 것

- 제공자가 템플릿 6칸을 손으로 쓰던 일 중 **값이 있는 칸의 85% 는 코드에서 근거와 함께 나온다.**
  남는 15%는 대부분 선택지를 좁히거나 표현을 바꾸는 **판단**이다.
- 제공자에게 남는 일은 셋으로 요약된다: ① 40개 후보 중 무엇을 물을지 고르기, ② 생성 조건 정하기,
  ③ 선택지를 좁히고 질문 문장 다듬기.
- 다음에 숫자를 키울 곳은 **생성 조건**(6개)과 **필드 밖 기본값**(2칸)이다. 생성 조건은 엔진이 그 필드를
  어떤 조건에서만 읽는지 따라가는 문제다 — `GenerateCondition` 의 주석이 이미 그 근거를 엔진 동작으로 적고
  있다(예: 실제 운행 대수는 `min(건물, 차량)`).

## 한계

- **시뮬레이터 하나**로만 쟀다. 정답지(손으로 쓴 템플릿)를 만든 사람이 같은 코드베이스를 다듬어 온
  사람이라 코드와 템플릿이 잘 맞물려 있다 — 다른 시뮬레이터에서는 일치율이 낮을 수 있다.
- 규칙이 이 코드의 관례(세터 · `fromName` · 오류 객체를 만드는 `if`)에 기대고 있다. 예외를 던지지 않고
  값을 조용히 보정하는 시뮬레이터에서는 범위가 나오지 않는다.
- 일치는 값이 같은지만 본다. 하한이 "이상" 인지 "초과" 인지는 템플릿 형식에 칸이 없어 채점하지 않는다
  (생성기는 `minExclusive` 로 남긴다).

## 재현 방법

```powershell
.\mvnw.cmd -q -pl template-generator -am install -DskipTests
.\mvnw.cmd -q -pl template-generator exec:java
```

결과는 `template-generator/target/` 의 `generated-templates.json` 과 `comparison.md` 다. 이 폴더의 두 파일은
그 실행 결과를 옮긴 것이다. 다른 시뮬레이터에는 `-Dexec.args="--source … --config … --validator … --out …"` 로
돌린다(`Main` 의 사용법).
