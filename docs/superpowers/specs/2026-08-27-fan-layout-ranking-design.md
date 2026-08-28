# 듀얼 40 mm 팬 배치 조합 랭킹 도구 설계

- 작성일: 2026-08-27
- 대상 모듈: `waste-sim-spring` / `com.wastesim.edge`
- 신규 MCP 도구: `rank_fan_layouts` (도메인 `EDGE`, `POST /mcp/edge`)
- 입력 자료: `dual_fan_all_layouts_preliminary.xlsx` 및 생성 스크립트 `build_fan_layouts.mjs` (2026-08-27)

---

## 1. 목적과 범위

라즈베리파이 5급 보드에 40×40 mm 팬 2개를 다는 위치·방향 조합 60가지를 전수 열거하고,
경험적 냉각점수로 순위를 매겨 **실측할 후보를 줄인다**.

이 도구는 CFD도 실측도 아니며, 기존 열 시뮬레이션 스택을 대체하지도 보완하지도 않는다.
역할은 오직 "어느 배치부터 실측할 것인가"에 답하는 **후보 선별기**다.

### 범위 밖

- 팬 풍량·정압, 함체 치수, 통풍구 개구율, 방열판 사양 반영
- 열저항 `R_ja` 산출, 스로틀링 진입시간(TTT), 에너지 계산
- 안전 판단이나 제품 정격 결정

---

## 2. 배경 — 왜 기존 도구에 넣지 않는가

`FanArraySpec` 클래스 주석과 `FAN_RPM_SWEEP_DESIGN.md` §12는 같은 규칙을 명시한다.

> 팬 위치·송풍 방향은 실험 조건을 보존하는 메타데이터일 뿐, 실측 보정 전까지
> 냉각계수를 임의로 만들어 내지 않는다.

그런데 이 설계가 이식하려는 점수 모델은 **정확히 위치 → 냉각계수를 만드는 모델**이다.
따라서 기존 물리 모델에 섞으면 그 규칙을 깬다. 대신 별도 도구로 분리하고,
**컴파일 의존성 수준에서 격리**한다(→ D-43).

정확도 우선순위상 이 모델의 자리는 맨 아래다.

```
경험적 배치 점수(이 도구)  ←  현재 위치
→ PWM 기반 RPM·전력 추정
→ TACH 실측 RPM
→ 실측 전류
→ PWM-RPM 보정곡선
→ RPM별 열저항 실측 보정
```

---

## 3. 아키텍처

```
[기존]  ThermalSimulator ← HeatsinkThermalModel ← FanArraySpec      (R_ja 기반 물리 모델)
           ↑ simulate_edge_throttling / simulate_heatsink_layout
             / sweep_fan_rpm / simulate_ptm_control

[신규]  FanLayoutScoreModel                                          (경험적 점수식)
           ↑ rank_fan_layouts
```

### 3.1 격리 규칙 (D-43)

신규 패키지 `com.wastesim.edge.layout`의 어떤 클래스도 다음을 import 하지 않는다.

- `ThermalSimulator`
- `HeatsinkThermalModel`
- `ThermalParams`, `ThermalRun`
- `FanArraySpec` (단 `FanArraySpec.SourceStatus` enum 값은 재사용한다)

의존성이 없으면 임시 계수가 기존 결과에 새어 들어갈 경로 자체가 없다.
이 규칙은 테스트로 고정한다(§7.1 "격리").

---

## 4. 점수 모델

`build_fan_layouts.mjs`와 엑셀 셀 수식에서 확인한 식을 그대로 이식한다.

### 4.1 장착 위치 (6곳)

| 위치 | 한글 라벨 | 높이 코드 | 측면 | 위치효율 |
|---|---|---:|---|---:|
| `BOTTOM` | 하단 | 0 | CENTER | 0.95 |
| `TOP` | 상단 | 2 | CENTER | 0.90 |
| `LEFT_BOTTOM` | 좌측 하단 | 0 | LEFT | 0.78 |
| `LEFT_TOP` | 좌측 상단 | 2 | LEFT | 0.82 |
| `RIGHT_BOTTOM` | 우측 하단 | 0 | RIGHT | 0.78 |
| `RIGHT_TOP` | 우측 상단 | 2 | RIGHT | 0.82 |

두 팬은 동일 사양이므로 위치쌍은 **순서 없는 조합**이다: `C(6,2) = 15`.
각 쌍에 방향 조합 4가지(흡기·흡기 / 흡기·배기 / 배기·흡기 / 배기·배기)를 곱해 **60조합**.

조합 ID는 열거 순서대로 `P01`~`P60`이며, 열거 순서는 위 표의 위치 순서를
바깥 루프 `i`, 안쪽 루프 `j > i`로 돌고 그 안에서 방향 4가지를 도는 순서로 고정한다.

### 4.2 계산식

```
pairFactor = 흡기·흡기 → 0.78
             배기·배기 → 0.82
             서로 다름 → 1.00

flowBonus  = 0
  두 팬의 방향이 다를 때(관통류)만:
    intake.level <  exhaust.level → +0.15   "자연대류와 같은 아래→위 흐름"
    intake.level >  exhaust.level → -0.10   "자연대류를 거스르는 위→아래 흐름"
    intake.level == exhaust.level →  0      "같은 높이의 횡류"
    intake.side == exhaust.side 이고 side != CENTER → -0.12  "; 입출구 단락 가능"
  두 팬의 방향이 같을 때:
    흡기·흡기 → "출구 면적에 따라 내부 양압"
    배기·배기 → "흡기 틈 위치에 따라 내부 음압"

coolingScore = clamp(0.25, 1.15, (eff1 + eff2) / 2 * pairFactor + flowBonus)

advisoryPeakTempC = 82 - coolingScore * 27
advisoryMeanTempC = advisoryPeakTempC - 5.2
advisorySpreadC   = 3 + (1 - coolingScore) * 10 + (방향이 같으면 2 else 0)

stagnationRisk = coolingScore >= 0.95 → LOW
                 coolingScore >= 0.78 → MEDIUM
                 그 외                → HIGH

flowType = 방향이 다르면 FORCED_THROUGH_FLOW
           흡기·흡기      POSITIVE_PRESSURE
           배기·배기      NEGATIVE_PRESSURE
```

### 4.3 명명 상수

계수는 전부 `FanLayoutScoreModel`의 `static final` 상수로 두고, 출처 주석
(엑셀 `가정` 시트, 2026-08-27)을 단다.

| 상수 | 값 |
|---|---:|
| `BARE_PEAK_ANCHOR_C` | 82.0 |
| `SCORE_TO_DELTA_C` | 27.0 |
| `MEAN_OFFSET_C` | 5.2 |
| `SPREAD_BASE` | 3.0 |
| `SPREAD_SLOPE` | 10.0 |
| `SAME_DIRECTION_SPREAD_PENALTY` | 2.0 |
| `SCORE_MIN` / `SCORE_MAX` | 0.25 / 1.15 |
| `SHORT_CIRCUIT_PENALTY` | -0.12 |
| `NATURAL_CONVECTION_BONUS` | +0.15 |
| `AGAINST_CONVECTION_PENALTY` | -0.10 |
| `INTAKE_PAIR_FACTOR` / `EXHAUST_PAIR_FACTOR` / `THROUGH_FLOW_FACTOR` | 0.78 / 0.82 / 1.00 |
| `RISK_LOW_THRESHOLD` / `RISK_MEDIUM_THRESHOLD` | 0.95 / 0.78 |

### 4.4 온도 값의 취급 (핵심 제약)

`advisoryPeakTempC = 82 - score*27`의 앵커 82℃와 계수 27은 **이 모델 안에서만
의미가 있는 임의값**이다. 기존 `simulate_edge_throttling` / `simulate_heatsink_layout`은
`R_ja`·주변온도·부하 프로파일에서 온도를 계산하므로, 같은 조건에서 두 도구가 서로 다른
온도를 낸다.

따라서 온도는 응답의 1급 필드가 아니라 `advisory` 블록에 격리하고, 다음 경고를
**항상** 함께 반환한다.

- `ADVISORY_TEMP_ANCHORED_ESTIMATE`
- `ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR`
- `FAN_SPEC_NOT_VERIFIED`

응답의 1급 판단 기준은 `coolingScore`·`rank`·`stagnationRisk`·`advisorySpreadC`다.

실측이 들어오면 `BARE_PEAK_ANCHOR_C`와 `SCORE_TO_DELTA_C`를
`calibrate_edge_thermal_model` 프로파일 값으로 대체할 수 있게 상수를 한곳에 모아 둔다.

---

## 5. 데이터 모델

신규 패키지 `com.wastesim.edge.layout`.

| 파일 | 역할 |
|---|---|
| `FanMountPosition.java` | enum 6개. 한글 라벨·높이 코드·측면·위치효율 보유. `parse()`는 영문 키와 한글 라벨을 모두 받는다 |
| `FanFlowRole.java` | enum `INTAKE`(흡기) / `EXHAUST`(배기). `parse()` 동일 |
| `FanLayoutCandidate.java` | record — `id`, `position1`, `flow1`, `position2`, `flow2` |
| `FanLayoutScore.java` | record — `coolingScore`, `flowType`, `pairFactor`, `flowBonus`, `advisoryPeakTempC`, `advisoryMeanTempC`, `advisorySpreadC`, `stagnationRisk`, `interpretation`, `sourceStatus` |
| `FanLayoutScoreModel.java` | 계수 상수 + `score(candidate)` + `enumerateAll(positions)` |
| `FanLayoutRanking.java` | record — `ranking`, `evaluatedCount`, `status`, `tieBreak`, `warnings`, `sourceStatus`, `recommendedMeasurementSteps` |
| `RankFanLayoutsTool.java` | `McpToolProvider` 구현 — 입력 검증·실행·응답 조립 |

`FanLayoutScore.sourceStatus`는 항상 `FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE`다.

---

## 6. MCP 도구 계약

### 6.1 입력 스키마

```json
{
  "type": "object",
  "properties": {
    "positions": {
      "type": "array",
      "description": "전수 열거에 포함할 장착 위치. 생략하면 6곳 전부",
      "items": {"type": "string",
        "enum": ["bottom","top","left_bottom","left_top","right_bottom","right_top"]}
    },
    "candidates": {
      "type": "array",
      "description": "직접 지정한 배치 후보. positions와 동시 사용 불가",
      "items": {
        "type": "object",
        "properties": {
          "fan1": {"type":"object","properties":{
            "position":{"type":"string"},"flow":{"type":"string","enum":["intake","exhaust"]}}},
          "fan2": {"type":"object","properties":{
            "position":{"type":"string"},"flow":{"type":"string","enum":["intake","exhaust"]}}}
        },
        "required": ["fan1","fan2"]
      }
    },
    "topK": {"type":"integer","description":"응답에 담을 상위 개수(1~60)","default":10},
    "includeAllCombinations": {"type":"boolean",
      "description":"true면 topK와 무관하게 평가한 모든 조합을 반환","default":false}
  }
}
```

### 6.2 입력 검증 (fail-closed)

| 규칙 | 오류 |
|---|---|
| `candidates`와 `positions` 동시 입력 | 거부 — 열거 범위와 직접 지정이 모순 |
| `positions` 원소가 2개 미만 | 거부 — 쌍을 만들 수 없다 |
| 알 수 없는 위치·방향 문자열 | 거부 |
| `positions`에 중복 | 거부 |
| `candidates`의 두 팬 위치가 같음 | 거부 — 한 자리에 팬 2개는 물리적으로 불가 |
| `topK` < 1 또는 > 60 | 거부 |
| NaN·Infinity | 거부 |

거부는 `ToolResult.rejected(...)`로 반환한다.

### 6.3 출력

```json
{
  "tool": "rank_fan_layouts",
  "status": "RANKED",
  "modelKind": "EMPIRICAL_SCORE_NOT_PHYSICS",
  "evaluatedCount": 60,
  "ranking": [{
    "rank": 1,
    "id": "P02",
    "fan1": {"position": "bottom", "positionKo": "하단", "flow": "intake", "flowKo": "흡기"},
    "fan2": {"position": "top", "positionKo": "상단", "flow": "exhaust", "flowKo": "배기"},
    "flowType": "FORCED_THROUGH_FLOW",
    "coolingScore": 1.075,
    "pairFactor": 1.0,
    "flowBonus": 0.15,
    "stagnationRisk": "LOW",
    "interpretation": "자연대류와 같은 아래→위 흐름",
    "advisory": {
      "peakTempC": 52.975,
      "meanTempC": 47.775,
      "spreadC": 2.25,
      "anchorBarePeakC": 82.0,
      "comparableWithSimulator": false
    }
  }],
  "tieBreak": "coolingScore 동률이면 advisorySpreadC가 작은 쪽, 그래도 같으면 조합 ID 오름차순",
  "warnings": ["FAN_SPEC_NOT_VERIFIED",
               "ADVISORY_TEMP_ANCHORED_ESTIMATE",
               "ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR"],
  "sourceStatus": "PRELIMINARY_ESTIMATE",
  "recommendedMeasurementSteps": [
    "상위 3개 배치 각 3회 반복",
    "무팬 및 팬 1개 기준선과 비교",
    "최고온도·노드별 온도·회복시간·소음·전력 기록"
  ]
}
```

### 6.4 정렬과 동률

정렬은 `coolingScore` **내림차순**, 동률이면 `advisorySpreadC` **오름차순**,
그래도 같으면 조합 ID 오름차순.

`advisoryPeakTempC = 82 - 27*score`는 `coolingScore`의 단조감소 변환이므로 이 정렬은
엑셀 `추천 결과` 시트의 정렬(`예상 최고온도` 오름차순 → `예상 편차` 오름차순)과
동일한 순서를 만든다. 응답에는 1급 필드인 `coolingScore` 기준으로 서술한다.

부동소수점 끝자리가 순위를 정하지 않도록 동률 판정에 상대 허용 오차 `1e-9`를 쓴다.

**동률은 실제로 발생한다 — 이 규칙은 이론적 방어가 아니다.** 60조합 중 P10(하단 흡기 +
좌측 상단 배기)과 P18(하단 흡기 + 우측 상단 배기)이 `coolingScore`·`advisorySpreadC`가
모두 완전히 같다(1.035 / 2.65). 좌·우가 대칭 계수를 갖기 때문이며, 같은 이유의 좌우 쌍이
여럿 더 있다. 따라서 최종 tie-break인 **조합 ID 오름차순이 2·3위를 실제로 가른다**.
엑셀 `추천 결과` 시트도 이 순서(P10이 2위)를 따르므로 결과가 일치한다.

이 동률은 모델의 한계를 그대로 드러낸 것이다 — 좌·우 구분이 없는 계수 집합에서는
좌측 배치와 우측 배치를 구별할 근거가 없다. 실측 전까지 임의로 우열을 만들지 않고
ID 순서로 고정한다.

---

## 7. 테스트

### 7.1 신규 `FanLayoutRankingTest.java`

| 항목 | 검증 내용 |
|---|---|
| 전수 열거 | 조합 60개, ID `P01`~`P60`, 중복 없음 |
| 골든 회귀 | P01·P02·P03·P05·P58·P59·P60의 `coolingScore`·`advisoryPeakTempC`·`advisorySpreadC`가 엑셀 값과 1e-9 이내 일치 |
| 점수 clamp (비활성 확인) | 6위치 표준 집합에서는 점수 범위가 `[0.58, 1.075]`라 clamp가 **한 번도 걸리지 않는다**. 60조합 전부가 열린 구간 `(0.25, 1.15)` 안에 있음을 단언해 이 가드가 현재 비활성임을 문서화한다 |
| 점수 clamp (경계 동작) | clamp 자체는 위치효율을 합성한 입력으로 직접 호출해 하한 0.25·상한 1.15가 동작함을 확인한다 |
| 단락 페널티 | 흡·배기가 같은 측면(CENTER 제외)일 때만 -0.12 적용. P58(우측 하단↔우측 상단)로 확인 |
| 자연대류 보정 | 흡기가 배기보다 낮으면 +0.15, 높으면 -0.10, 같으면 0 |
| 순위 | 1위 P02(하단 흡기 + 상단 배기), 동률 시 `advisorySpreadC` 작은 쪽 |
| 좌우 대칭 동률 | P10과 P18이 점수·편차 모두 동일하고, ID 오름차순으로 P10이 2위가 된다(§6.4) |
| 대칭성 | 위치쌍 순서 무관 — 같은 조합이 두 번 열거되지 않음 |
| 경고 | 응답에 경고 3종이 항상 포함, `sourceStatus == PRELIMINARY_ESTIMATE` |
| 격리 | `com.wastesim.edge.layout` 패키지 소스에 `ThermalSimulator`·`HeatsinkThermalModel`·`ThermalParams`·`ThermalRun` import가 없음 |
| 입력 검증 | §6.2의 7개 거부 규칙 각각 |

골든 값(엑셀 대조):

| ID | 위치·방향 | coolingScore | advisoryPeakTempC | advisorySpreadC |
|---|---|---:|---:|---:|
| P01 | 하단 흡기 + 상단 흡기 | 0.7215 | 62.5195 | 7.785 |
| P02 | 하단 흡기 + 상단 배기 | 1.075 | 52.975 | 2.25 |
| P03 | 하단 배기 + 상단 흡기 | 0.825 | 59.725 | 4.75 |
| P05 | 하단 흡기 + 좌측 하단 흡기 | 0.6747 | 63.7831 | 8.253 |
| P58 | 우측 하단 흡기 + 우측 상단 배기 | 0.83 | 59.59 | 4.70 |
| P59 | 우측 하단 배기 + 우측 상단 흡기 | 0.58 | 66.34 | 7.20 |
| P60 | 우측 하단 배기 + 우측 상단 배기 | 0.656 | 64.288 | 8.44 |

### 7.2 기존 파일 보강

- `EdgeChatRoutingTest` — §8의 라우팅 케이스 8개
- `EdgeMcpToolsTest` — `tools/list`에 `rank_fan_layouts`가 `EDGE` 도메인으로 노출

구현은 TDD로 진행한다: 골든 값 테스트를 먼저 쓰고 `FanLayoutScoreModel`을 구현한다.

---

## 8. 채팅 라우팅

### 8.1 위험 — 기존 패턴과의 충돌

새 도구의 어휘가 기존 패턴 둘과 겹친다.

1. `HEATSINK` 패턴이 `배치|위치|어디\s*에?\s*(붙|달|장착|부착)`을 이미 잡는다.
   "팬을 어디에 달아야 해?"가 방열판 후보 순위표로 새면, `EdgeToolSelector` 주석에
   실측 회귀로 기록된 오라우팅과 같은 유형의 결함이 된다.
2. `SWEEP` 패턴의 `최적\s*(의\s*)?(...|팬|속도|...)`가 "**최적 팬** 배치"를 먼저 잡는다.

### 8.2 검사 순서

```
PTM → FAN_LAYOUT(신규) → SWEEP → CALIBRATE → HEATSINK → THROTTLING
```

### 8.3 `FAN_LAYOUT` 패턴 규칙

팬 어휘와 배치 어휘가 **함께** 있을 때만 매칭하고, 다른 도구의 주제 어휘가 함께 있으면
물러난다(리뷰 두 라운드를 거쳐 좁혀진 최종 형태 — 아래 8.4의 회귀 케이스가 이 좁히기를
강제한다).

- 팬 축: `팬 | fan | 쿨러 | 흡기 | 배기 | 흡배기`
- 배치 축: `배치 | 조합 | 어디\s*에?\s*(달|붙|장착|부착) | 어느\s*(위치|자리|쪽) |
  어떤\s*(방향|위치|자리) | (위치|방향)\s*(조합|비교|추천|고르|정하)`
  — 맨 `위치`·`방향`은 단독으로 넣지 않는다(리뷰 라운드 1: CSV 실측 데이터의
  "위치"나 냉각 조건 설명의 "방향"이 배치 랭킹으로 새던 결함). 비교·배치를 실제로
  요청하는 동사·명사와 붙어 있을 때만 본다.
- **부정 가드**(리뷰 라운드 2, 최종 리뷰): 팬 축·배치 축이 모두 매칭돼도 다음 어휘가
  문장에 함께 있으면 FAN_LAYOUT은 매칭하지 않고 원래 검사 순서(SWEEP → CALIBRATE →
  HEATSINK)로 넘긴다 — `스윕|sweep|rpm|알피엠|pwm|회전수|속도|가성비|전력|보정|캘리브|
  calibrat|csv|실측|방열판|히트싱크`. `최적`은 가드에 넣지 않는다 — "최적 팬 배치"가
  SWEEP의 `최적...팬`에게 도로 뺏기기 때문이다(8.1 참고).

`EdgeToolSelector`에 `TOOL_LAYOUT = "rank_fan_layouts"` 상수를 추가한다.
`select()`의 반환 후보가 다섯에서 여섯으로 늘어난다.

### 8.4 라우팅 회귀 케이스

| 입력 | 기대 도구 | 이유 |
|---|---|---|
| "팬 두 개를 어디에 달아야 제일 시원해?" | `rank_fan_layouts` | 팬 + 배치, 가드 어휘 없음 |
| "흡기 배기 조합 중 뭐가 나아?" | `rank_fan_layouts` | 팬 + 배치, 가드 어휘 없음 |
| "최적 팬 배치 알려줘" | `rank_fan_layouts` | SWEEP보다 먼저 검사, "최적"은 가드 대상 아님 |
| "40mm 팬 2개 위치 조합 전부 비교해줘" | `rank_fan_layouts` | 팬 + 배치, 가드 어휘 없음 |
| "팬 두 개를 어디에 어떤 방향으로 달아야 제일 시원해?" | `rank_fan_layouts` | 팬 + 배치, 가드 어휘 없음 |
| "최적 팬 rpm은?" | `sweep_fan_rpm` | 배치 어휘 없음 |
| "팬 몇 %가 가성비 좋아?" | `sweep_fan_rpm` | 배치 어휘 없음 |
| "팬 rpm 조합을 스윕해줘" | `sweep_fan_rpm` | 팬+조합이 매칭돼도 `스윕`·`rpm`이 가드에 걸려 물러남 |
| "팬 속도와 전력 조합 중 가성비가 제일 좋은 건?" | `sweep_fan_rpm` | `속도`·`전력`·`가성비`가 가드에 걸려 물러남 |
| "팬 흡기 배치 데이터를 CSV로 기록했는데 모델 보정할 수 있어?" | `calibrate_edge_thermal_model` | `csv`·`보정`이 가드에 걸려 물러남 |
| "방열판을 어디에 붙일까?" | `simulate_heatsink_layout` | 팬 어휘 없음 |
| "팬 달린 상태에서 방열판 배치 비교해줘" | `simulate_heatsink_layout` | `방열판`이 가드에 걸려 물러남 |
| "쿨러 달았을 때 방열판 배치 비교해줘" | `simulate_heatsink_layout` | `방열판`이 가드에 걸려 물러남 |
| "팬을 미리 돌리면 이득이야?" | `simulate_ptm_control` | PTM이 먼저 |

---

## 9. 채팅 응답과 UI

- `ChatMessage.MessageType`에 `EDGE_LAYOUT` 추가, 전용 필드 `edgeLayout` 추가.
  `EDGE_SWEEP`/`edgeSweep`과 같은 패턴이다 — 응답 모양이 다른데 같은 필드에 담으면
  클라이언트가 모양을 보고 추측해야 한다(`FAN_RPM_SWEEP_DESIGN.md` §17.5와 같은 근거).
- `ChatController`에 `TOOL_LAYOUT` 분기와 `EDGE_LAYOUT` 메시지 생성 헬퍼.
- `EdgeChatFormatter`에 텍스트 요약 — 1위 조합·냉각점수·기류 해석,
  그리고 "이 온도는 상대 비교용 임시 예측" 한 줄.
- `edge.js`에 `edgeLayoutBanner()` / `edgeLayoutChart()` / `edgeLayoutTable()`.
  상위 N개 가로 막대(냉각점수 기준), 표 열은 위치·방향·흐름유형·냉각점수·정체위험·해석.
  advisory 온도 열에는 셀 단위 "임시" 뱃지를 붙여 시뮬레이터 온도와 시각적으로 구분한다.

---

## 10. 명세 반영

`docs/specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_12.md`
(현재 저장소 최대 번호: FR-114 / UT-267 / D-42)

| 번호 | 내용 |
|---|---|
| FR-115 | 듀얼 팬 배치 조합 전수 열거 — 6위치에서 15쌍 × 4방향 = 60조합 |
| FR-116 | 경험적 냉각점수 산출과 순위·동률 규칙 |
| FR-117 | advisory 온도의 분리 표기와 경고 3종 |
| FR-118 | 자연어 → `rank_fan_layouts` 라우팅과 기존 5도구 대비 우선순위 |
| UT-268~280 | §7의 테스트 항목 |
| D-43 | 배치 점수 모델을 기존 열 스택과 **컴파일 의존성 수준에서** 격리한 이유 |
| SDD 2.15 계열 | 신설 절 — 아키텍처·점수식·격리 규칙 |

`docs/reference/FAN_RPM_SWEEP_DESIGN.md` §12(면적·배치 효율 판정의 한계)에
이 도구가 그 한계를 **대체하지 않는다**는 상호 참조를 한 줄 추가한다.

---

## 11. 열린 항목

없음. 실측이 들어온 뒤의 재보정(앵커 82℃·계수 27 대체)은 이 설계의 범위 밖이며,
§4.3에서 상수를 한곳에 모아 그 작업의 진입점만 남긴다.
