# 포항시 교통량 연동 시뮬레이션 확장 설계서

**대상 시스템:** waste-sim-spring (v1.3, MCP 도구 서버 + DEVS 쓰레기 시뮬레이션)
**목적:** 포항시 교통량 데이터를 "교통 레이어"로 추가하여, 폐기물 레이어와 **교차 분석**해 수거 정책을 최적화하는 4개 시나리오를 구현한다.
**독자:** 이 문서는 코딩 에이전트(Claude Code)가 그대로 구현할 수 있도록 클래스·필드·메서드·JSON 스키마·검증 규칙·테스트까지 명세한다.
**상태:** 신규 확장 제안 — 아래 항목은 현재 코드에 **아직 없으며** 이 문서에 따라 추가한다.

---

## 0. 설계 대원칙 (베이스라인 유지)

이 확장은 시스템의 기존 안전 철학을 그대로 지킨다. 시나리오들이 "LLM이 정책을 결정"하는 것처럼 보이지만, 실제 권한 분리는 다음과 같다.

- **LLM은 제안(structure)만 한다.** 자연어를 교통·폐기물을 고려한 파라미터 JSON으로 변환하거나, 대안을 "브리핑" 텍스트로 제시한다. (베이스라인 C1)
- **서버가 처분(decision)한다.** 교통·폐기물 교차 검증(`TrafficAwareValidator`)이 실행 가능성을 판정하고, 불가능·위험·적대적 요청을 **차단**한다(fail-closed, C3/C5).
- **결정론 우선.** 실행 여부의 1차 게이트(`TimeExpressionDetector`)와 교차 검증은 결정론적이며 LLM-free다(C2).

핵심 한 줄: **"LLM이 제안하고, 교통 인지 검증기가 막는다."**

---

## 1. 현행 시스템 접점 (무엇에 붙이나)

| 기존 구성요소 | 확장 방식 |
|---|---|
| `model/SimulationConfig` | 교통·차량·경로 필드 추가 |
| `model/OccupationType` (enum) | 신규 `model/TruckType` enum 추가(별도 파일) |
| `simulation/SimulationEngine` | `CollectEvt` 이동시간에 교통 가중치 반영, 경로 순서·시차 배차 반영, 교통 유발 민원 집계 |
| `service/ScenarioService` | 교통 시나리오 메서드 추가 |
| `tool/SimulationConfigValidator` | 교통·폐기물 교차 검증 규칙 추가 → `TrafficAwareValidator`로 확장 |
| `tool/ErrorCode` | `CRITICAL_WASTE_ACCUMULATION`, `TRAFFIC_INFEASIBLE`, `TRUCK_COUNT_ZERO` 추가 |
| `tool/SimulationTool` | `runSimulation`/`runScenario` 실행 전 교차 검증 호출, 신규 `updateRouteSequence` 경로 |
| `mcp/McpToolCatalog` | `run_waste_simulation` 스키마에 교통 파라미터 추가, 신규 도구 `run_traffic_scenario`·`update_route_sequence` |
| `tool/ConfigArgs` | 신규 JSON 필드 → `SimulationConfig` 매핑 |
| `service/OpenAiService` | 시스템 프롬프트에 교통 레이어 컨텍스트 주입, 트레이드오프 브리핑 응답 유형 |

---

## 2. 신규 데이터 모델 — 교통 레이어

### 2.1 `model/TrafficProfile.java` (신규)

포항시 교통량 데이터를 시간대별·구역별 혼잡 가중치로 표현한다.

```java
public class TrafficProfile {
    private String id;                         // 예: "jangryang-weekday"
    private double[] hourlyWeight;             // 길이 24, 시간대별 통행시간 배수(1.0=평시, 2.2=피크)
    private Map<String, double[]> nodeHourlyWeight; // 수거장 노드별 시간대 가중치(선택)
    private double congestionThresholdRed;     // RED(극심) 판정 가중치, 예: 2.0
    // getters/setters
    /** 특정 분(minuteOfDay)·노드의 혼잡 가중치 반환(노드 미지정 시 전역) */
    public double weightAt(int minuteOfDay, String node) { ... }
    public boolean isRed(int minuteOfDay, String node) { return weightAt(minuteOfDay,node) >= congestionThresholdRed; }
}
```

**데이터 소스/포맷:** `src/main/resources/traffic/jangryang-weekday.json` 등에 시간대별 가중치를 둔다. 로더 `service/TrafficDataService`가 클래스패스에서 로드하고 id로 조회한다. (실측 포항시 데이터가 없으면 08:00–09:00=2.2, 14:00 골목=1.8 등 문서상 가정값으로 시드 데이터를 만든다.)

```json
{
  "id": "jangryang-weekday",
  "congestionThresholdRed": 2.0,
  "hourlyWeight": [1.0,1.0,1.0,1.0,1.0,1.1,1.4,1.9,2.2,2.0,1.5,1.3,1.4,1.5,1.6,1.5,1.6,2.1,2.0,1.5,1.2,1.1,1.0,1.0],
  "nodeHourlyWeight": { "Node_B_Yangdeok": [ ... 24개 ... ] }
}
```

### 2.2 `model/TruckType.java` (신규 enum)

```java
public enum TruckType {
    LARGE_5TON  ("5톤", 60.0, 1.0, false),   // 용량 큰 대신 골목 진입 불가, 정체 유발 큼
    MEDIUM_2P5T ("2.5톤", 30.0, 1.2, true),
    SMALL_1TON  ("1톤",  12.0, 1.6, true);   // 용량 작지만 기동성·골목 진입 우수
    public final String labelKo;
    public final double capacityKg;      // 트럭 1대 수거 용량
    public final double mobilityFactor;  // 이동속도 배수(클수록 정체 영향 적음)
    public final boolean alleyAccess;    // 이면도로(골목) 진입 가능 여부
    // 생성자/조회 fromName
}
```

---

## 3. `SimulationConfig` 확장 필드

`model/SimulationConfig.java`에 아래 필드 + getter/setter + `copy()` 반영. 모두 기본값 있어 하위호환.

| 필드 | 타입 | 기본값 | 의미 |
|---|---|---|---|
| `trafficEnabled` | boolean | false | 교통 레이어 사용 여부 |
| `trafficProfileId` | String | null | 적용할 `TrafficProfile` id |
| `truckType` | String | "LARGE_5TON" | 차량 종류(TruckType) |
| `truckCount` | int | (= 기존 numTrucks 재사용) | 투입 대수 |
| `dispatchIntervalMinutes` | int | 0 | 트럭 간 시차 배차(분). >0이면 대수만큼 분산 출발 |
| `routeSequence` | List\<String> | null | 수거장 방문 순서(노드 id). null이면 기본 순서 |
| `trafficComplaintWeight` | double | 1.0 | 교통 유발 민원 가중(RED 구간 통과 시) |

> 참고: 기존 `numTrucks`, `routeTravelMinutes`, `collectionTimesMinutes`를 재사용/확장한다. `truckCount`는 `numTrucks`의 별칭으로 두거나 통합한다.

---

## 4. DEVS 엔진 연동 (`SimulationEngine`)

기존 수거 이벤트 생성 로직(`CollectEvt`, `routes`, `travel = routeTravelMinutes`, `t = d*DAY + slot + pos*travel`)을 교통 인지형으로 확장한다.

1. **이동시간 교통 가중:** 노드 `pos`의 도착 시각 계산 시 이동시간에 교통 가중치를 곱한다.
   `effectiveTravel = routeTravelMinutes / truckType.mobilityFactor * trafficProfile.weightAt(currentMinuteOfDay, node)`
   → 피크(2.2배)엔 뒤 노드일수록 도착이 크게 늦어져 `수거장 C` 민원이 오른다(시나리오 1·2 근거).
2. **경로 순서:** `routeSequence`가 있으면 그 순서로 노드를 방문(기본 round-robin 대신). 정체 노드를 후순위로 미루는 동적 라우팅 지원(시나리오 2).
3. **시차 배차:** `dispatchIntervalMinutes>0`이면 트럭 k의 출발을 `slot + k*interval`로 어긋나게 해 골목 동시 진입을 분산(시나리오 3).
4. **골목 접근 제약:** `truckType.alleyAccess==false`이고 노드가 골목(alley) 태그면 진입 페널티/불가 처리.
5. **교통 유발 민원:** 트럭이 RED 구간을 통과하면 `total += trafficComplaintWeight` 로 별도 채널 `byOcc["Traffic"]`에 집계 → 결과에 "교통 정체 민원" 노출.

결과 DTO(`SimulationResult`)에 `trafficComplaints`, `avgCompletionMinutes`(전체 수거 완료 시간) 필드를 추가해 트레이드오프를 정량화한다.

---

## 5. 검증 계층 — `TrafficAwareValidator`

`SimulationConfigValidator`를 확장(또는 래핑)하여 교통·폐기물 교차 검증을 수행한다. **실행 전** `SimulationTool`에서 호출한다.

### 5.1 신규 `ErrorCode`

```java
TRUCK_COUNT_ZERO,             // 운행 대수 0 (수거 불가)
CRITICAL_WASTE_ACCUMULATION,  // 예측 적재율이 한계 초과(수거 중단/부족)
TRAFFIC_INFEASIBLE            // 교통 제약상 실행 불가(골목 대형트럭 등)
```

### 5.2 검증 규칙

| 규칙 | 조건 → 결과 |
|---|---|
| V-T1 | `truckCount < 1` → `TRUCK_COUNT_ZERO` (시나리오 4 차단) |
| V-T2 | 예측 적재율 검사: 주어진 정책으로 수거량 < 배출량이 지속돼 예측 적재율 > 1.2(120%) → `CRITICAL_WASTE_ACCUMULATION` (시나리오 4) |
| V-T3 | `truckType` 골목 진입 불가 + 대상 구역이 골목 → `TRAFFIC_INFEASIBLE` |
| V-T4 | `routeSequence`의 노드가 실제 수거장 집합과 불일치 → `INVALID_ARGUMENTS` |
| V-T5 | (경고, 비차단) 수거 시각이 RED 피크 구간이면 `warning`에 트레이드오프 사유 첨부 → LLM 브리핑용 |

> V-T2의 "예측 적재율"은 별도 정밀 시뮬 없이, 일일 배출총량 대비 수거 용량(`truckCount * truckType.capacityKg * 수거횟수`)으로 근사 계산하는 결정론 함수 `predictOverflowRatio(cfg)`로 구현한다.

---

## 6. MCP 도구 확장 (`McpToolCatalog`)

### 6.1 `run_waste_simulation` 스키마에 교통 파라미터 추가

`RUN_SIM_SCHEMA`의 properties에 아래를 병합:

```json
"trafficEnabled": {"type": "boolean", "default": false},
"trafficProfileId": {"type": "string", "description": "예: jangryang-weekday"},
"truckType": {"type": "string", "enum": ["LARGE_5TON","MEDIUM_2P5T","SMALL_1TON"], "default": "LARGE_5TON"},
"truckCount": {"type": "integer", "default": 1, "minimum": 1},
"dispatchIntervalMinutes": {"type": "integer", "default": 0},
"routeSequence": {"type": "array", "items": {"type": "string"}}
```

### 6.2 신규 도구 `update_route_sequence`

동적 라우팅(시나리오 2) 전용. 기존 base 설정에 경로 순서만 갈아끼워 재실행.

```json
{
  "type": "object",
  "properties": {
    "routeSequence": {"type": "array", "items": {"type": "string"},
      "description": "수거장 방문 순서. 예: [\"Node_A\",\"Node_C\",\"Node_B\"]"},
    "collectionTime": {"type": "string"},
    "trafficProfileId": {"type": "string"}
  },
  "required": ["routeSequence"]
}
```

`SimulationTool`에 `ToolResult updateRouteSequence(SimulationConfig base, List<String> route)` 추가 → V-T4 검증 후 실행.

### 6.3 `McpController.callTool` 라우팅에 신규 도구 case 추가.

---

## 7. LLM 오케스트레이션 확장 (`OpenAiService`)

### 7.1 컨텍스트 주입
시스템 프롬프트(`EXTRACTION_SYSTEM_PROMPT`, `PLAIN_ANSWER_SYSTEM_PROMPT`)에 "교통 레이어"를 설명하는 블록을 추가한다: 조회 가능한 필드(시간대 가중치, RED 판정), 그리고 **"피크 시각 수거는 도심 정체 민원을 유발하니, 무조건 실행하지 말고 대안 시각/차량/경로를 제안하라"**는 지침.

### 7.2 트레이드오프 브리핑 응답
1단계 게이트가 실행으로 분류돼도, 추출된 파라미터가 V-T5(피크) 경고에 걸리면 `SimulationTool`이 `ToolResult.rejected`가 아니라 **`ToolResult.needsConfirm`**(신규) 을 반환하고, LLM `answerPlain`이 트레이드오프 브리핑 텍스트를 생성한다. 프론트는 CONFIRM 버블로 "06:30 새벽 수거로 앞당김" vs "10:00 이후" 대안을 제시한다.

> LLM은 대안 **파라미터를 제안**할 뿐이며, 실제 실행은 사용자가 확인 버튼을 눌러 검증을 통과한 설정만 돌린다(C1/C3 유지).

---

## 8. 4개 시나리오 상세 매핑

### 시나리오 1 — 출퇴근 피크 타임의 트레이드오프
| 항목 | 내용 |
|---|---|
| 자연어 입력 | "원룸촌 쓰레기 넘친다고 난리야. 오늘 아침 8시 반까지 수거 트럭 전부 투입해서 빨리 해결해줘." |
| 데이터 교차 | 폐기물: 적재율 85%(위험) / 교통: 08:00–09:00 가중치 2.2배(RED) |
| 게이트/추출 | 시각 1개(08:30) → 실행 후보. 추출 `collectionTime=08:30, truckCount=전체` |
| 교차 검증 | V-T5 경고 발동(08:30 ∈ RED). 즉시 실행 대신 `needsConfirm` |
| LLM 제어 | 대안 파라미터 제안: `runSimulation(collectionTime="06:30", truckCount=3)`(피크 전 새벽) 또는 "8시 반은 정체가 심해 10시 이후 수거를 제안" 브리핑 |
| 기대 결과 | 두 정책의 (수거완료시간·위생민원·교통민원) 비교표 반환, 트레이드오프 시각화 |
| 구현 포인트 | V-T5, needsConfirm, 트레이드오프 브리핑, DEVS 교통 가중 이동시간 |

### 시나리오 2 — 돌발 정체구역 우회 및 수거 순서 재편성
| 항목 | 내용 |
|---|---|
| 자연어 입력 | "지금 양덕사거리 방면 정체가 너무 심하네. 오늘 수거 동선 알아서 최적화해줘." |
| 데이터 교차 | 원 경로 `[A]→[B(양덕사거리)]→[C]` / 실시간 `Node_B` 혼잡도 최고치 |
| MCP 출력 | `update_route_sequence(routeSequence=["Node_A","Node_C","Node_B"])` (B를 후순위) |
| 교차 검증 | V-T4(노드 유효성) 통과 → 실행 |
| 기대 결과 | 정체 노드 후순위로 전체 수거 완료 시간 단축 증명(`avgCompletionMinutes` 비교) |
| 구현 포인트 | `routeSequence` DEVS 반영, `update_route_sequence` 도구, `nodeHourlyWeight` |

### 시나리오 3 — 차량 스케일 다운 및 빈도 분산
| 항목 | 내용 |
|---|---|
| 자연어 입력 | "원룸가 골목길 정체 유발 안 하면서 주간 쓰레기 적재율 관리해봐." |
| 데이터 교차 | 이면도로(골목) 혼잡 데이터, 대형트럭 진입 시 골목 마비 |
| LLM 제어 | 대형 1대 대신 소형 다수 시차 투입 제안 |
| MCP 출력 | `{ "truckType": "SMALL_1TON", "truckCount": 3, "dispatchIntervalMinutes": 45 }` |
| 교차 검증 | V-T3(SMALL_1TON은 alleyAccess=true 통과), 2단계 JSON 가드레일 통과 |
| 기대 결과 | 정체(교통민원)와 적재율(위생민원) 동시 관리되는 정책 확인 |
| 구현 포인트 | `TruckType`, `dispatchIntervalMinutes` DEVS 시차 배차, alleyAccess |

### 시나리오 4 — 가드레일이 극단적 정책을 차단 (Adversarial)
| 항목 | 내용 |
|---|---|
| 자연어 입력 | "오늘 장량동 교통 체증 제로로 만들고 싶어. 오늘 하루 모든 수거 트럭 운행을 중단해." |
| 데이터 교차 | 교통: 만족(트럭 0) / 폐기물: 24h 중단 시 적재율 150% 초과, 위생민원 폭발 |
| 게이트/검증 | 1단계 게이트 통과 후 `TrafficAwareValidator`에서 V-T1(truckCount=0) + V-T2(overflow>1.2) 발동 |
| 결과 | **실행 차단(Blocking)**, 인간 참여형(Confirm)으로 유도 |
| 출력 에러 | `ApiError(code=CRITICAL_WASTE_ACCUMULATION, message="교통 정체는 방지되나 쓰레기 적재량이 한계를 초과하여 요청을 수행할 수 없습니다.")` |
| 구현 포인트 | `TRUCK_COUNT_ZERO`/`CRITICAL_WASTE_ACCUMULATION`, `predictOverflowRatio`, fail-closed |

---

## 9. 테스트 케이스 (신규)

| ID | 대상 | 입력 | 기대 |
|---|---|---|---|
| UT-T1 | TrafficProfile.weightAt | 08:30 조회 | 2.2 반환, isRed=true |
| UT-T2 | predictOverflowRatio | truckCount=0 | ratio ≥ 1.5 |
| UT-T3 | Validator V-T1 | truckCount=0 | TRUCK_COUNT_ZERO |
| UT-T4 | Validator V-T2 | 24h 수거중단 정책 | CRITICAL_WASTE_ACCUMULATION |
| UT-T5 | Validator V-T3 | LARGE_5TON + 골목 | TRAFFIC_INFEASIBLE |
| UT-T6 | Engine 교통 가중 | 08:30 vs 06:30 수거 | 08:30 완료시간·교통민원 ↑ |
| UT-T7 | routeSequence | [A,C,B] vs [A,B,C] (B 정체) | [A,C,B] 완료시간 ↓ |
| UT-T8 | dispatchInterval | SMALL×3 interval=45 | 골목 동시 진입 0, 적재율 관리 |
| IT-T1 | MCP update_route_sequence | routeSequence 유효 | isError=false |
| IT-T2 | MCP run(truckCount=0) | 시나리오 4 | isError=true, CRITICAL_WASTE_ACCUMULATION |

---

## 10. 구현 순서 (코딩 에이전트 체크리스트)

1. `model/TruckType.java`, `model/TrafficProfile.java` 추가.
2. `service/TrafficDataService.java` + `resources/traffic/jangryang-weekday.json` 시드 데이터.
3. `SimulationConfig`에 §3 필드 + getter/setter + `copy()` 반영.
4. `SimulationResult`에 `trafficComplaints`, `avgCompletionMinutes` 추가.
5. `SimulationEngine` §4 교통 가중 이동시간·경로 순서·시차 배차·교통민원 집계.
6. `tool/ErrorCode`에 §5.1 코드 추가.
7. `tool/SimulationConfigValidator` → §5.2 규칙(`predictOverflowRatio` 포함).
8. `tool/ConfigArgs`에 신규 JSON 필드 매핑.
9. `tool/SimulationTool`에 교차 검증 호출 + `updateRouteSequence` + `needsConfirm` 경로.
10. `mcp/McpToolCatalog` §6 스키마·도구, `McpController` 라우팅.
11. `service/OpenAiService` §7 프롬프트 컨텍스트·브리핑.
12. `service/ScenarioService`에 교통 시나리오 비교 메서드(선택).
13. §9 테스트 작성 → `mvn test`.

---

## 11. 베이스라인 원칙 준수 확인

| 원칙 | 이 확장에서의 준수 |
|---|---|
| C1 (LLM은 구조만) | LLM은 파라미터 제안·브리핑만, 실행/차단은 서버 |
| C2 (결정론 라우팅) | 실행 게이트·교차 검증·overflow 예측 모두 결정론 함수 |
| C3 (fail-closed) | 위험·적대적 요청은 검증기가 실행 전 차단 |
| C5 (검증은 서버) | 모든 교통·폐기물 규칙은 `TrafficAwareValidator`(서버 코드) |

시나리오 4가 이 설계의 핵심 증명이다: 사용자가 자연어로 극단·적대적 명령을 넣어도, **결정론적 서버 검증기가 폐기물 도메인 붕괴를 예측해 차단**하고 인간 확인으로 유도한다. 교통 최적화(사용자 의도)와 위생 안전(도메인 무결성) 사이의 트레이드오프를 시스템이 자동으로 방어한다.
