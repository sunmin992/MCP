# SES 원본화 — 시뮬레이터마다 SES 트리 하나를 원본으로 둔다

2026-11-02 · 대상 모듈 `simulator`, `template-generator` · 대상 시뮬레이터 장량동(8090)

## 1. 왜

교수님 지적: "SES를 왜 썼나", "요청을 SES로 어떻게 바꾸나", "LLM 의존이 크다". 지금 시스템은 이 질문들에 답할 근거가 약하다.

- 시뮬레이터가 아는 SES 는 **결정 지점 목록**뿐이다. 트리(엔티티 분해·특수화 가지·결합)는 9월 22일(`13530df5`) 연구 키트와 함께 지워져 어디에도 없다.
- 같은 결정이 손으로 쓴 파일 두 곳에 따로 적혀 있다.
  - 능력 카드 `sesDigest.decisionPoints`(14개): 브로커가 매칭에 `path` · `matchKeys` 를 읽는다.
  - 템플릿 `jangnyang-templates.json`(19개): 서브태스크 · 질문 · PES 가 쓴다.
  - 둘은 이미 어긋나 있다. 경로가 다른 결정이 2개다. `occupationMix` 는 카드에서 `거주민/직업유형`, 템플릿에서 `건물/거주민집합/직업구성` 이고, `dischargeTimeMode` 도 같은 식으로 다르다. 또 카드에 없는 결정이 5개 있다(`collectionTimesMinutes`, `zoneAssignmentRule`, `intraZoneTravelMinutes`, `days`, `seeds`).
  - 카드의 `field` 는 템플릿의 `configField` 와 같은 값이다(예: `numTrucks`). 이것은 어긋남이 아니다.
- PES 의 `sesId: jangnyang-ses` 는 서버에 없는 문서를 가리킨다. 그래서 PES 가 그 SES 의 유효한 가지인지 검사할 수 없다. 예를 들어 교통 미반영인데 교통 프로파일 값이 실려 있어도 지금은 조용히 통과한다.

## 2. 목표

1. **원본:** 시뮬레이터마다 SES 트리 파일 하나가 원본이다. 장량동은 `simulator/src/main/resources/ses/jangnyang-ses.json`이다. 결정 노드에 템플릿 정보가 붙는다.
2. **파생물:** 템플릿 JSON 과 능력 카드의 `sesDigest` 는 트리에서 **만들어 낸다.** 손으로 고치지 않는다.
3. **제공:** 서버가 `get_ses` 도구로 트리 전체를 sesId · 버전과 함께 낸다.
4. **PES 가지 검증:** `build_scenario` 가 PES 를 그 트리의 유효한 가지인지 검사한다.
5. **초안 추출:** template-generator 가 시뮬레이터 소스에서 SES 트리 **초안**을 결정적으로 뽑는다. 제공자는 초안을 보고 원본을 확정한다. LLM 은 쓰지 않는다.

## 3. 비목표 (따로 설계한다)

- **개체별 펼치기:** 다중 분해 아래 결정을 `차량[1]/차종`처럼 개체마다 서브태스크로 만드는 것.
- **목적 관련 노드만 묻기:** 요청의 결과 지표 · 비교 변수와 이어진 노드만 질문하는 것.
- **북구(8091) · 포항 주차(8092):** 다른 저장소다. 카드 형식이 그대로이므로 브로커는 영향받지 않는다.
- **결합(couplings)의 자동 추출:** 제공자가 쓴다.

## 4. 실패로 보는 것

- 사용자가 겪는 동작(질문 · 서브태스크 상태 · 시나리오 · 브로커 판정)이 바뀌는 것. 예외는 6-3 의 가지 검증이 새로 막는 경우뿐이다.
- 트리와 파생물이 어긋난 채 빌드가 통과하는 것.
- 초안 추출이 실행마다 다른 결과를 내는 것.

## 5. 정한 것

| 항목 | 결정 | 누가 |
|---|---|---|
| 초안 방식 | 코드에서 결정적 추출 → 제공자 확정 (LLM 불사용) | 사용자 |
| 범위 | 이번에는 원본화만. 개체별 펼치기 · 목적 관련 질문은 따로 | 사용자 |
| 트리 형식 | ref-v8 의 엔티티 형식(`attrs`, `decompositions[kind=aspect/spec/multi]`, `couplings`)을 잇고, 결정 노드에 `decision` 블록을 붙인다 | 내 판단 — 이미 연구에서 쓴 형식이다 |
| 파생물 위치 | 템플릿 JSON 과 카드의 `sesDigest` 는 **생성해서 커밋**한다. 원본과 다르면 실패하는 테스트로 지킨다 | 내 판단 — 아래 근거 |
| templateId · answerKey · sesPath | 지금 값을 그대로 유지한다 | 내 판단 — 실험 기록 · 테스트가 이 값을 쓴다 |

**파생물을 생성해서 커밋하는 이유**

- 브로커 테스트(`TestCards`)와 template-generator 비교 테스트가 카드 · 템플릿 파일을 **디스크에서 직접** 읽는다.
- 실행 중에 만들어 내면 이 테스트들과 baseline 실험 스키마가 원본을 따로 해석해야 한다.
- 커밋 + 어긋남 테스트 방식이면 실행 경로(`TemplateCatalog`, `BrokerRegistrar`)는 바뀌지 않는다.
- 비용: 원본을 고친 뒤 생성 명령을 한 번 돌려야 한다. 잊으면 테스트가 잡는다.

## 6. 어떻게

### 6-1. 트리 원본 (`jangnyang-ses.json`)

```json
{
 "sesId": "jangnyang-ses", "sesVersion": "1.1.0",
 "root": "장량동 생활쓰레기 수거",
 "entities": {
  "장량동 생활쓰레기 수거": {"decompositions": [{"kind": "aspect", "name": "시스템 구성", "children": ["수거차량집합", "건물집합", "..."]}]},
  "수거차량집합": {"label": "수거차량 집합",
    "decompositions": [{"kind": "multi", "name": "수거차량 다중", "children": ["수거차량"]}],
    "specs": [{"name": "차량유형", "children": ["LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"]}],
    "attrs": ["배차간격", "경로적재몫"]},
  "경로교통": {"couplingDecisions": ["교통결합"], "...": "..."}
 },
 "couplings": [{"from": "경로교통.교통결합", "to": "수거차량.이동", "note": "..."}],
 "decisions": [
  {"at": "수거차량집합/차량유형", "templateId": "jn.truckType", "answerKey": "truckType", "...": "템플릿 칸",
   "card": {"matchKeys": ["차종", "..."], "choices": ["..."], "default": "LARGE_5TON", "...": "카드 칸"}}
 ]
}
```

- **트리(`entities`):** 구조만 담는다. 엔티티 키는 경로 문자열이다(`경로교통/교통구역집합`처럼 `/` 를 포함할 수 있다). 하위 요소는 `decompositions`(aspect/multi), `specs`(이름 + 선택지 children), `attrs`(이름), `couplingDecisions`(이름)이다. 루트를 뺀 모든 엔티티 키는 어떤 분해의 children 으로 닿아야 한다.
- **결정(`decisions`):** 순서 있는 목록이다. 이 순서가 템플릿 순서다(지금 순서 유지). 각 결정은 `at` 으로 트리 노드를 가리킨다.
  - `at` 해석: `at` 과 같거나 `at` 의 `/` 앞부분과 같은 엔티티 키 중 가장 긴 것을 찾는다.
  - `at` 이 엔티티 키 자체이면 그 엔티티의 multi 분해 → MULTI_ASPECT
  - 나머지 이름이 spec 이름이면 SPECIALIZATION, attr 이름이면 ATTRIBUTE, couplingDecision 이름이면 COUPLING_CONDITION
  - 어디에도 없으면 불러오기 실패다.
- **nodeKind · sesPath:** 둘 다 파생값이다. sesPath 는 `at` 이고, nodeKind 는 위 해석 결과다. 템플릿에 손으로 적던 두 칸은 없어진다.
- **결정 블록:** 나머지 칸은 지금 템플릿 칸을 그대로 둔다(`templateId`, `answerKey`, `label`, `valueType`, `generateWhen`, `allowed`, `min`, `max`, `unit`, `defaultValue`, `defaultBasis`, `question`, `configField` 등, 지금 파일에 있는 그대로).
- **`card` 칸:** 카드 decisionPoint 의 `path` · `kind` · `field` 를 뺀 나머지 칸을 그대로 담는다. `card` 가 없는 결정은 digest 에 나가지 않는다.
- **특수화 선택지:** SPECIALIZATION 결정의 `allowed` 는 그 spec 의 children 과 같아야 한다.
- **`label`:** 엔티티의 `label` 은 digest `entities` 에 나갈 이름이다. label 이 있는 엔티티만 나가고, 순서는 `entities` 순서다. 지금 카드의 7개와 같게 맞춘다.
- **sesVersion:** 형식이 바뀌므로 1.1.0 으로 올린다.
- **엔티티 이름:** 지금 sesPath 의 앞부분과 같게 고른다(`수거차량집합`, `경로교통/교통구역집합` 등).

### 6-2. 파생 (`SesDerivation`, simulator 모듈)

- **digest 칸 대응:** 카드의 `path` 는 sesPath(`at`)이고, `field` 는 `configField` 이며, `kind` 는 nodeKind 다. 나머지는 `card` 칸 그대로다. 순서는 `decisions` 순서이므로 `routeAvailableCapacityKg` 가 `capacity` 앞으로 한 칸 옮겨진다(내 판단 — 브로커는 순서를 쓰지 않는다).
- `templates(ses)`: 템플릿 JSON 을 만든다. 지금 파일과 **칸 하나하나가 같아야** 한다. 바뀌는 것은 sesVersion 뿐이다.
- `digest(ses)`: 카드의 `sesDigest` 를 만든다. `entities` 는 트리의 엔티티이고, `decisionPoints` 는 `matchKeys` 가 있는 결정만 담는다. 지금 카드에 없는 5개는 `matchKeys` 를 두지 않으므로 계속 빠진다. 그래서 14개가 그대로 나오고, 브로커 판정(`matchKeys` 로 함)은 바뀌지 않는다.
  - **경로 2개는 템플릿 쪽으로 맞춘다.** `occupationMix` · `dischargeTimeMode` 의 카드 `path` 가 템플릿 sesPath 로 바뀐다. 이것은 내 판단이다. 결정이 놓인 자리는 거주민 한 명이 아니라 거주민 집합의 구성이다.
  - 브로커는 이 `path` 를 매칭 근거 문자열로만 싣는다. 이 두 문자열을 단언하는 테스트는 없다. `broker/data/registry.json` 은 서버가 다시 등록할 때 새로 쓰인다.
- **생성 명령:** 어긋남 테스트가 `-Dses.regenerate=true` 로 돌면 두 파일을 다시 쓴다. Maven 플러그인을 늘리지 않는다. 카드는 `sesDigest` 칸만 바꾼다.
- **어긋남 테스트:** 원본에서 만든 결과와 커밋된 파일이 다르면 실패하고, 실패 메시지에 생성 명령을 적는다. 비교는 바이트가 아니라 JSON 트리 동등성이다.
- **쓰기 형식:** Jackson pretty-print(들여쓰기 2, UTF-8)로 쓴다. 카드는 파일 전체를 읽어 `sesDigest` 칸만 바꾼 뒤 키 순서를 유지해 다시 쓴다. 첫 생성 때 서식 diff 가 한 번 생기는 것은 받아들인다(내 판단).

### 6-3. 서버 제공과 PES 가지 검증

- **`SesCatalog`(@Component):** 트리를 불러온다. 불러올 때 두 가지를 검사한다.
  - 템플릿 카탈로그와 sesId · 버전이 같은가
  - 모든 decision 의 `configField` 가 `SimulationConfig` 게터로 있는가 (PesBackVerifier 와 같은 반사 방식). 지금 19개 모두 `configField` 가 있고 중첩 경로는 없다. 결합 조건도 `trafficEnabled` 를 갖는다. 따라서 `configField` 는 필수 칸이다.

  어긋나면 서버가 뜨지 않는다.
- **`get_ses` 도구:** 트리 원본을 그대로 낸다. 설명문에 "결정 노드의 answerKey 가 get_templates 의 answerKey 와 같다"를 적는다.
- **`SesBranchCheck` (`ScenarioBuilder` 의 실행 설정마다):**
  1. **모르는 키:** 이미 `PesFlattener` 가 템플릿에 없는 답변키를 거부한다. 템플릿이 트리에서 나오므로 이것이 곧 "트리에 없는 키" 검사다. 새로 만들지 않는다.
  2. **가지친 쪽 값:** `planner.plan(values)` 가 NOT_GENERATED 로 판정한 결정에 값이 실려 있으면 막는다. 메시지는 "가지친 쪽의 값입니다 — 빼십시오"이다.
     - **비교 변수:** `ScenarioBuilder` 는 이미 비교 값마다 그 값을 넣은 `values` 로 `planner.plan` 을 따로 부른다. 그 실행 단위 결과로 판정하므로, 값마다 생성 여부가 갈리는 결정은 그 값의 실행에서만 막힌다.
     - **출처:** `Pes.origins` 에서 읽는다. `Pes` 가 출처 없는 값을 이미 거부하므로 출처 없는 경우는 없다.
     - **예외:** 출처가 `MODEL_DEFAULT` 인 가지친 값은 막지 않고, 평탄화 전에 PES 에서 뺀다. 이것은 내 판단이다. LLM 이 기본값을 일괄로 넣는 흐름을 깨지 않으려는 것이다. `USER` · `DERIVED` 출처는 막는다.
     - **보류(DEFERRED)인 결정의 값:** 막지 않는다. 생성될 수도 있는 결정이고, 보류 자체는 기존 흐름이 이미 "먼저 정하라"고 알린다.
  3. **선택지 · 범위:** 이미 validator 가 하므로 다시 하지 않는다.
- 막힌 항목은 기존 `blocks` 에 섞지 않고, 기존 `backBlocks` 처럼 별도 목록(`branchBlocks`)으로 응답에 싣는다.

### 6-4. 초안 추출 (template-generator)

- **새 출력:** `--ses-draft out.json`. 지금 뽑는 템플릿 후보 40개를 트리 초안으로 감싼다.
- **결정 종류 판정 규칙:** 규칙이 정하고, 같은 입력이면 같은 결과다.

  | 코드의 모양 | 초안의 자리 |
  |---|---|
  | enum 으로 해석되는 필드 | `specs` (선택지 = enum 상수) |
  | 이름이 `num*` / `*Count` 이고 값 종류가 정수인 필드 (엔진의 반복은 분석하지 않는다 — 내 판단, 단순화) | `decompositions[kind=multi]` 후보 + `_review: "무엇의 개수인지 확인"` |
  | 그 밖의 필드 | `attrs` |
  | 생성 조건 | 지금처럼 조건식 초안을 남기고, 런타임 조건이 섞이면 `_review` 에 적음 |

- **계층:** 코드에서 정할 수 없다. 설정 클래스가 평평하기 때문이다. 초안은 모든 결정을 루트 아래 평평하게 두고 `_review: "엔티티 배치 필요"` 를 붙인다. 엔티티 이름 · 배치 · 결합은 제공자가 쓴다.
- **대조:** 지금 `TemplateComparator` 가 하는 일(손 템플릿 ↔ 생성 후보, 생성 조건 진리표)을 그대로 쓴다. 손 템플릿이 이제 원본에서 만들어진 파일이기 때문이다. 여기에 원본 트리의 결정 종류와 초안의 결정 종류가 다른 것을 표로 낸다.

## 7. 확인 방법

- 템플릿 파생 결과가 지금 파일과 sesVersion 외에 같다.
- digest 파생 결과가 지금 카드의 14개 decisionPoints 와 `matchKeys` · `field` · `kind` 가 같다. `path` 는 위 2개만 다르다. 카드에만 있는 부가 칸(`homogeneity`, `derived`, `presets` 같은 것)은 decision 블록으로 옮겨 그대로 나오게 한다.
- 브로커 테스트 149개와 FirstRoundAccuracyTest 수치가 그대로다.
- 가지 검증 테스트:
  - 교통 IGNORE + 교통 프로파일 값(USER 출처) → 막힘
  - 같은 값이 MODEL_DEFAULT 출처 → 버리고 통과
  - 차량 1대 + 배차 간격 값 → 막힘
  - 트리에 없는 키 → 막힘
- `get_ses` 가 원본을 그대로 낸다.
- 초안 추출을 두 번 돌리면 바이트까지 같다.

## 8. 빌더에게 맡기는 것

- `SesDerivation` · `SesCatalog` · `SesBranchCheck` 의 내부 구조와 클래스 이름
- 생성 명령의 정확한 형태(Maven exec 또는 테스트 클래스의 main)
- 엔티티 이름 · 계층 · 결합 내용: 지금 sesPath 와 카드 `entities` 를 따르고, ref-v8(`git show 13530df5^:waste-sim-spring/docs/research/s1-ses-extraction/reference-ses.json`)을 골격 참고로만 쓴다. 근거 행번호는 옮기지 않는다.
