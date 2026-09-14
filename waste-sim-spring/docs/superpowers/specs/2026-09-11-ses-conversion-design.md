# 장량동 시뮬레이터의 SES 구조 변환 — 설계

2026-09-11 · 코드 `develop @ 8879ad7` · 참조 SES `ref-v7`

## 왜 하는가 — 검증하려는 가설

> 시뮬레이션을 체계요소구조(SES)로 변환하고 서브태스크 템플릿으로 결정해야 할 매개변수를
> 명시하면, LLM은 사용자의 답변으로 서브태스크를 채워 시나리오를 생성하고 사용자 요청에 맞는
> 시뮬레이션을 구성할 수 있다.

이 작업의 목적은 코드를 깨끗하게 만드는 것이 아니라 **이 문장을 시험대에 올리는 것**이다.

지금 상태로는 가설이 검증되지 않는다. 34개 문항(`jangnyang-simulator-v4.json`)은 사람이 손으로
적은 목록이고, SES(`reference-ses.json`, ref-v7)는 그 옆에 따로 서 있는 문서다. 둘이 이어져
있지 않으므로 "SES가 물어볼 것을 정해 준다"는 주장은 **가정돼 있을 뿐 검증된 적이 없다**.

변환의 산출물은 그 이음이다.

## 출발점 — 지금 있는 것

```
채팅 요청 → RequestInterpreter(LLM 추출) → BlueprintComposer → 34문항 답변 수집
  → JangnyangScenarioBuilder.toConfig()  ← if문 덩어리. 암묵적으로 이미 가지치기를 한다
  → SimulationConfig (평평한 세터 529줄)
  → SimulationEngine.run() (330줄 한 메서드)
```

`toConfig()`가 하는 일은 세 가지로 나뉜다. 이것이 곧 SES 가지치기의 세 연산이다.

| toConfig가 하는 일 | SES에서의 이름 | 예 |
|---|---|---|
| enum을 보고 분기한다 | **spec 축 선택** | `travelTimeMode` · `dischargeTimeMode` · `collectionSchedule` · `truckType` · `scenarioType` |
| 개수를 정한다 | **multi 복제 수** | `numBuildings` · `residentsPerBuilding` · `truckCount` · `occupationPreset` |
| 값을 옮긴다 | **속성 바인딩** | 나머지 15개 직접 대응 |

가지치기는 이미 일어나고 있다. 다만 if문에 흩어져 있어 트리로 보이지 않을 뿐이다.

## 결정 1 — 기준 SES는 하나, 「설정」은 유도된 뷰

SES가 두 개 있었다. 이번에 받은 SVG(`ses-overview.svg`)와 코드 대조본 `ref-v7`이다.

| | SVG | ref-v7 |
|---|---|---|
| 루트 자식 | 시뮬레이션 **설정** / 수거모델 / 시나리오 실험 / **실행 엔진** / 결과 | 대상 시스템 / 실험 / 관측 |
| 관점 | 무엇을 결정해야 하는가 | 무엇으로 이루어져 있는가 |
| 실행 엔진 | 엔티티로 있음 | 일부러 제외 |
| 거주민 직업 | …·**기타 직업** | …·**야간 교대근무자**·**1인 직장인** |
| 폐기물 유형 속성 | 종류·배출비율·**부피계수** | 종류·배출비율·**용량·임계값·수거주기·수거요일** |

**관점은 SVG가 가설에 맞고, 내용은 ref-v7이 맞다.** ref-v7만 코드와 한 줄씩 대조돼 있고,
「기타 직업」·「부피계수」는 그 대조에서 교정되거나 코드에 없다고 확인된 것이다.

그런데 SVG를 그대로 옮기면 **같은 매개변수를 두 번 적게 된다** — `거주민·배출 설정{~거주민수,
~직업구성, ~1인배출량}`과 `거주민{~직업, ~외출시각, ~배출량, ~귀가시각}`이 같은 것을 두 자리에
적는다. 손으로 맞춰야 하는 자리가 둘이 되고, 갈라지면 어느 쪽이 옳은지 알 수 없다.

**그래서 트리는 ref-v7 하나만 두고, 「시뮬레이션 설정」은 가지치기 지점을 모아 보여 주는 파생
뷰로 만든다.** SVG가 주는 이득(결정할 매개변수가 한자리에 명시된다)은 그대로 얻으면서 손으로
관리하는 두 번째 가지는 생기지 않는다.

이 편이 가설에도 맞다. 가설은 "결정할 매개변수를 **명시하면**"이라고 하는데, 그 명시가 손으로
적은 목록이면 아무것도 검증되지 않는다. 트리에서 유도돼야 "SES 구조가 매개변수를 정해 준다"가
주장이 된다.

`실행 엔진`은 SES에 넣지 않는다 — ref-v7의 판단("실행 수단이지 대상의 성질이 아니다")을 따르되,
문항은 필요하므로 **SES 밖 결정**으로 따로 표시한다.

## 결정 2 — SES 선언은 코드에, 대조는 테스트로

`reference-ses.json`을 런타임에 읽지 않는다. 그 파일은 LLM 추출 실험의 **정답지**다. 제품 코드가
그것을 읽기 시작하면 실험 대상과 실험 도구가 한 파일을 공유하게 되고, 채점 기준을 고치는 일이
시뮬레이터 동작을 바꾸게 된다.

`JangnyangEntityStructure`에 Java로 선언하고, **테스트 한 개**가 그 선언과
`reference-ses.json`이 구조적으로 같은지 확인한다. 갈라지면 테스트가 깨지되 어느 쪽이 옳은지는
사람이 정한다.

## 구조 — 새 패키지 `com.wastesim.ses`

| 타입 | 역할 |
|---|---|
| `SesEntity` | 이름 · 속성 목록 · 분해 목록 |
| `Decomposition(Kind, name, children)` | `ASPECT` / `SPEC` / `MULTI` |
| `EntityStructure` | 엔티티 51개를 담은 트리 |
| `Coupling(from, to, mechanism, activeWhen)` | ref-v7의 결합 6개 |
| `DecisionPoint` | 가지치기 지점 (아래) |
| `PrunedStructure` (PES) | spec 축이 하나로 접히고, multi 복제 수가 정해지고, 속성에 값이 붙은 트리 |
| `SesPruner` | 답변 → `PrunedStructure` |
| `PesFlattener` | `PrunedStructure` → `SimulationConfig` |

```java
sealed interface DecisionPoint {
    record SpecChoice(String entity, String axis, List<String> options) {}
    record MultiCount(String entity, String setEntity) {}
    record AttributeValue(String entity, String attribute, ValueKind kind) {}
    record OutsideSes(String field, String why) {}
}
```

## 문항 세트를 SES에서 유도한다

유도되는 것과 사람이 쓰는 것을 가른다. **이 경계가 곧 가설이 주장하는 범위다.**

| 문항의 구성요소 | 어디서 오나 |
|---|---|
| `answerField` | 지점 ↔ 필드 대응 (`구성결정-SES-대응표.md` 34행이 이미 가지고 있다) |
| `answerType` | `SpecChoice`→`ENUM`, `MultiCount`→`INTEGER`, `AttributeValue`→속성의 값 종류 |
| `allowedRange.values` | **spec 축의 자식 이름들 그대로** — 차종 축 → 5톤·2.5톤·1톤 |
| `group` | 지점이 달린 엔티티의 상위 가지 |
| `required` | spec 축·multi 개수는 필수 — 정해지지 않으면 트리가 닫히지 않는다 |
| `question` · `retryQuestion` | **사람이 쓴다** — 자연어 문면 |
| `validationRule` · `completionCondition` | **사람이 쓴다** |
| `basis` | **사람이 쓴다** — 출처·근거는 구조가 모른다 |

문면은 `subtask/jangnyang-prose-v5.json`에 SES 지점 ID를 키로 두고, 유도된 골격과 병합해
`JangnyangSubtaskDefinition`을 만든다. 기존 카탈로그·세션·검증기는 그대로 쓴다 — 세트 v5가 하나
더 생기는 모양이다.

**34문항은 이렇게 닫혀야 한다.**

```
29  SES 가지치기 지점에서 유도한 필드     ← 가설이 주장하는 부분(지점 28개, 필드 29개)
 1  SES 밖 결정 (engine — 실행 수단)
 4  절차 제어 (simulationGoal · defaultApproval
             · inputAndScenarioConfirmed · executionApproval)
──
34
```

29라는 수는 `구성결정-SES-대응표.md`가 코드 대조로 세어 놓은 것이다. **지점 수와 필드 수는
다르다(M3)** — `attr:수거차량:수거시각` 지점 하나가 `collectionTime`(단일값)·
`collectionTimes`(목록) 두 답변 필드를 낸다(Ruling 2). 그래서 SES에서 유도되는 것은
정확히는 **지점 28개, 필드 29개**다. "29 SES 가지치기 지점"이라는 표현은 지점과 필드를
섞어 써서 실제보다 지점이 하나 많다고 말한다.

## 가지치기가 요청 처리 중에 일어난다

```
채팅 요청
  → RequestInterpreter (LLM 추출)
  → BlueprintComposer → 답변 수집          ← 문항이 이제 SES 유도본
  → SesPruner:    답변 → PES               ← 가지치기가 일어나는 자리
  → PesFlattener: PES → SimulationConfig
  → SimulationEngine.run()
```

PES는 **이 사용자의 요청이 SES의 어느 가지를 골랐는지**를 담은 객체다. 5톤 차량 가지를 골랐고,
건물은 10개로 복제했고, 이동시간은 구간 상수 방식을 골랐다 — 이것이 눈에 보이는 형태로 남는다.

이것이 중요한 이유: 가설이 "요청에 맞는 시뮬레이션을 **구성**할 수 있다"인데 **"맞다"를 판정할
물건이 지금은 없다**. `SimulationConfig`는 숫자 뭉치라 사용자가 원한 대로 구성됐는지 보기 어렵지만,
PES는 고른 가지가 그대로 보인다. 미리보기 화면에 그대로 쓸 수 있다.

## 결과 동일성 — 같은 시드에 같은 숫자

1. **골든 테스트 먼저.** 시나리오 여러 개 × 시드별 `SimulationResult` 스냅샷을 지금 엔진으로 떠 둔다
2. **`toConfig()`를 지우지 않고 참조 구현으로 남긴다**
3. **차등 테스트.** 답변 조합마다 `toConfig(answers)`와 `flatten(prune(answers))`가 필드 단위로
   같은지 비교한다
4. 3이 통과한 뒤에야 `toConfig()`를 제거한다

엔진을 손대지 않으므로 1은 1단계에서 자동으로 통과한다. 진짜 검증은 3이고, 숫자가 아니라
`SimulationConfig` 필드를 비교하므로 어디가 틀렸는지 바로 나온다.

## 2단계 — 커플링으로 엔진을 잇는다

1단계가 끝나도 `SimulationEngine.run()`은 여전히 전부를 하는 한 메서드다. 2단계는 그것을 SES
엔티티대로 쪼개고 커플링으로 잇는다. ref-v7에 결합 6개가 코드 줄 번호까지 확인돼 있다.

| 보내는 쪽 | 받는 쪽 | 코드 위치 |
|---|---|---|
| 거주민.배출 | 수거지점.적재 | `SimulationEngine:457` |
| 수거지점.적재량 | 적재초과 판정 | `:459-462` |
| 수거지점.적재량 | 임대인 점검 판정 | `:466-475` |
| 수거차량.수거 | 수거지점.비움 | `:420` |
| 교통구역.혼잡계수 | 수거 경로.이동시간 | `:336, :343` (`trafficMode=APPLY`일 때만) |
| 교통구역.혼잡계수 | 교통혼잡 판정 | `:337-339` (`trafficMode=APPLY`일 때만) |

마지막 두 개는 사용자가 "교통 안 씀"으로 답하면 **통째로 죽는다**. 즉 답변 하나가 값이 아니라
**모델의 연결 구조를 바꾼다**. 가설 입장에서 이것이 "가지치기가 구조를 정한다"의 가장 선명한
예다.

2단계는 **1단계가 통과한 뒤에** 착수한다. 엔진 내부를 건드리면 숫자가 흔들릴 위험이 실제로 있고,
그때 되돌아갈 지점이 1단계다.

## 가설을 무엇으로 판정하는가

기준을 미리 정해 둔다. 정해 두지 않으면 결과를 보고 기준을 만들게 된다.

**측정 1 — 문항이 SES에서 나오는가.** 자동 유도한 뼈대와 손으로 적은 34문항을 대조한다. 29개가
맞아떨어지면 "SES가 물어볼 것을 정해 준다"가 성립한다. **완전히 같을 것으로 보지 않는다** —
어긋나는 자리가 나오면 그것이 발견이다. "SES에 자리가 있는데 묻지 않던 것"과 "묻고 있는데 SES에
자리가 없는 것"을 둘 다 보고한다.

**측정 2 — 답변이 시뮬레이션을 구성하는가.** 차등 테스트가 전부 통과하면 "SES 경로만으로
시뮬레이션을 구성할 수 있다"가 성립한다.

**측정 3 — LLM이 실제로 채울 수 있는가.** 이미 있는 것을 쓴다(`llm_benchmark.py`, S1 추출 키트).
자연어 요청을 주고 LLM이 문항을 채워 PES까지 가는지 본다.

1과 2가 이번 작업의 산출물이고, 3은 그 위에서 돌린다.

## 범위 밖

- `reference-ses.json`을 런타임에 읽지 않는다 — 실험 정답지와 제품 코드를 섞지 않는다
- python 엔진(`PythonWasteSimAdapter`) 쪽은 건드리지 않는다
- `ses-overview.svg`는 코드가 확정된 뒤에 다시 그린다 — 지금 그리면 또 낡는다
- DEVS 형식(atomic/coupled, 시간전진 함수) 도입은 하지 않는다

## 아직 정해지지 않은 것

- ref-v7의 `couplings` 포트 이름과 CP-4 소속은 미검증이다(`참조-검증-기록.md`). 2단계에서
  커플링을 코드로 옮길 때 확정된다
- 문면 파일 `jangnyang-prose-v5.json`을 v4에서 옮겨 쓸지 다시 쓸지는 유도 결과를 보고 정한다
