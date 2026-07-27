# MCP 모델 연결 아키텍처 — 엔진 어댑터 · 독립 도구 확장점 · 엣지 연동 계획

> 원래 별개 문서였던 `MCP_모델_연결_방법.md`(장량동 Java/Python 엔진을 MCP 도구로 연결한
> 방법)와 `엣지_라즈베리파이_MCP_연동_방법.md`(장량동과 무관한 독립 도구 확장점 + 라즈베리파이
> 엣지 모델 연동 계획)를 하나로 합쳤다 — 둘 다 "이 MCP 서버에 모델을 어떻게 연결하는가"를
> 다루는 같은 주제라 따로 두면 내용이 어긋나기 쉬웠다(실제로 예전 문서의 "새 모델 추가 표준
> 절차"가 이후 추가된 독립 확장점을 반영 못 해 낡아 있었다).

## 1. 현재 구조

`waste-sim-spring`은 하나의 MCP 서버(`POST /mcp`, JSON-RPC 2.0)를 갖고 있다.

- `McpController` — `initialize`/`tools/list`/`tools/call`/`ping` 처리.
- `McpToolCatalog` — 도구 목록·JSON Schema 정의.
- `SimulationTool` — 검증(`SimulationConfigValidator`) → 실행을 캡슐화하는 파사드. MCP·REST·채팅 세 진입점이 전부 이 한 곳만 호출한다.
- `SimulationEngine` — 장량동 쓰레기 시뮬레이션을 실제로 계산하는 Java 이벤트 큐 엔진.

이 서버에 새 모델/도구를 연결하는 확장점은 **두 가지**다 — 어느 쪽을 쓸지는 그 모델이
장량동 `SimulationConfig`(수거시각·트럭·교통 등)를 그대로 쓰는 엔진 변형인지, 아니면 전혀
다른 입력을 가진 독립 도구인지에 따라 갈린다.

## 2. 확장점 A — `SimulationModelProvider` (장량동 엔진 변형용, 구현 완료)

```java
// com.wastesim.mcp.SimulationModelProvider
public interface SimulationModelProvider {
    String modelId();          // 예: "java-devs", "python-devs"
    String toolName();         // 예: "run_waste_simulation", "run_waste_simulation_devs"
    String description();
    String inputSchemaJson();
    ToolResult run(SimulationConfig cfg);   // 이미 SimulationConfigValidator 검증을 통과한 설정
}
```

`SimulationModelRegistry`가 스프링이 자동 주입하는 `List<SimulationModelProvider>`를 모아
`tools/list`·`tools/call` 라우팅에 자동 반영한다 — 새 모델을 추가해도 `McpController`/
`McpToolCatalog` 자체는 손댈 필요가 없다(Open/Closed 원칙).

**중요한 제약**: `McpController.callTool()`은 이 인터페이스로 등록된 도구를 전부 무조건
`ConfigArgs.fromJson()`으로 `SimulationConfig`로 변환하고 `SimulationTool`이 `SimulationConfigValidator`
(수거시각 범위, days/seeds 범위, 트럭 대수 등 장량동 도메인 규칙)를 통과시킨다. 즉 이 확장점은
"아무 모델이나 꽂는 슬롯"이 아니라 **"장량동 SimulationConfig를 쓰는 엔진 변형 전용 슬롯"**이다.

### 2.1 등록된 구현체

| 구현체 | modelId | toolName | 설명 |
|---|---|---|---|
| `JavaEngineProvider` | `java-devs`(기본) | `run_waste_simulation` | 기존 Java 재구현 엔진(`SimulationEngine`) |
| `PythonWasteSimAdapter` | `python-devs` | `run_waste_simulation_devs` | 원본 논문 재현 Python/pyevsim 엔진, 서브프로세스(`ProcessBuilder`)로 `waste_sim.mcp_bridge` 호출 |

### 2.2 Python 엔진 연결 방식(구현 완료 요약)

- `waste_sim/mcp_bridge.py`(Python 쪽) — stdin으로 JSON 설정을 받아 `build_and_run()`을
  `seeds`만큼 반복 실행하고, 평균·표준편차·직업별 평균(0건인 직업군도 항목 유지 —
  `DESIGN_DECISIONS.md` D-11과 동일 원칙)을 집계해 stdout에 JSON 한 줄로 낸다.
  이후 트래픽 파라미터(`trafficEnabled`/`trafficProfileId`/`routeTravelMinutes`)도
  추가로 받도록 확장됐다.
- `PythonWasteSimAdapter`(Java 쪽) — `python -m waste_sim.mcp_bridge`를 서브프로세스로
  실행해 JSON을 주고받는다. 실행 파일 경로·프로젝트 루트·타임아웃은
  `application.properties`(`waste-sim.python.executable`, `waste-sim.python.project-root`,
  `waste-sim.python.timeout-seconds`)로 뺐다. Python 쪽 결과 필드명은 Java
  `SimulationResult`와 억지로 맞추지 않고 원본 JSON 그대로 노출한다 — MCP 클라이언트가
  어느 엔진 결과인지 구분할 수 있게 하려는 의도적 설계.
- 검증: 같은 설정(12:00, 10일×10시드)을 두 엔진에 나란히 호출해 확인 — Java 6.9±4.3건,
  Python 7.4±3.0건으로 완전히 같지는 않지만(난수 알고리즘이 다름) 같은 경향(학생 직업군이
  지배적, 생산직·주부는 0건)을 보였다. 공통 검증 게이트(`days=0` 등 범위 밖 값)가 두 도구
  모두 동일하게 차단하는 것도 확인했다.
- 정확한 코드는 `src/main/java/com/wastesim/mcp/PythonWasteSimAdapter.java`,
  `waste_sim/mcp_bridge.py` 참고.

## 3. 확장점 B — `McpToolProvider` (독립 도구/모델용, 구현 완료·미사용)

```java
// com.wastesim.mcp.McpToolProvider
public interface McpToolProvider {
    String toolName();          // 예: "predict_edge_throttling"
    String description();
    String inputSchemaJson();   // 이 도구 전용 스키마 — RUN_SIM_SCHEMA와 무관하게 자유롭게 정의
    ToolResult call(JsonNode args);   // 원본 JSON 그대로 — SimulationConfig 변환·검증 없음
}
```

`SimulationModelProvider`의 제약(장량동 `SimulationConfig` 고정) 때문에, 라즈베리파이
발열/스로틀링 예측 모델처럼 전혀 다른 입력(온도·클럭·FPS 등)을 가진 도구를 위해 별도
확장점을 새로 만들었다. `McpToolRegistry`가 같은 패턴(스프링이 `List<McpToolProvider>`
자동 수집)으로 등록된 구현체를 모으고, `McpToolCatalog.toolsList()`·`McpController.callTool()`이
이 레지스트리도 함께 조회하도록 이미 배선돼 있다.

**현재 상태**: 등록된 구현체가 하나도 없어(빈 리스트) 기존 도구 목록에 영향이 없다 —
확장점만 준비된 상태. `McpControllerTest`에 가짜 독립 도구(`FakeIndependentTool`)로
등록·라우팅·검증 미적용을 확인하는 테스트 3개가 있다.

**나중에 실제 모델을 붙일 때 할 일은 이것뿐이다**: `McpToolProvider`를 구현하는 클래스
하나(예: `EdgeThrottlingModelAdapter`)를 만들어 `@Component`로 등록하고, 그 안에서 학습된
모델을 호출(Java로 포팅하거나 `PythonWasteSimAdapter`처럼 서브프로세스로 Python 호출)해
`ToolResult`로 감싸 반환하면 끝난다. `McpController`·`McpToolCatalog`는 다시 손댈 필요가 없다.

## 4. 새 모델/도구를 추가하는 표준 절차

1. **어느 확장점인지 먼저 판단** — 새 모델이 수거시각·트럭·교통 등 장량동 `SimulationConfig`
   파라미터를 그대로 쓴다 → §2(`SimulationModelProvider`). 전혀 다른 도메인/입력이다(라즈베리파이
   발열 예측 등) → §3(`McpToolProvider`).
2. **모델을 호출 가능한 형태로 준비** — 언어 무관, "입력 JSON → 출력 JSON" 진입점만 있으면
   된다(같은 프로세스 라이브러리든, 서브프로세스든, 별도 HTTP 서비스든 무방).
3. **인터페이스 구현체 작성** — 어느 쪽이든 메서드 4~5개만 채우면 끝.
4. **`@Component`로 등록** — 스프링이 자동으로 카탈로그·라우팅에 포함시킨다. 컨트롤러·카탈로그
   자체는 손댈 필요 없음.
5. **검증 규칙** — `SimulationModelProvider`는 공통 `SimulationConfigValidator`를 자동으로
   거친다. `McpToolProvider`는 구현체가 직접 검증하고 실패 시 `ToolResult.rejected`로 반환한다.
6. **테스트 추가** — 어댑터 단위 테스트(정상/오류/타임아웃) + 필요하면 기존 모델과의 비교
   회귀 테스트.
7. **채팅 라우팅(선택)** — 사용자가 자연어로 "이 모델로 실행해줘"라고 했을 때 어떤 도구로
   갈지 결정론적 감지기(`EngineSelectionDetector`와 같은 패턴, 정규식 기반)를 하나 추가한다.
   LLM이 실행 여부/모델 선택을 직접 판단하게 하지 않는다(C2 원칙 — 아래 §6 참고).

## 5. 아키텍처 요약도

```
                         POST /mcp (JSON-RPC)
                                │
                        McpController
                                │
                        McpToolCatalog (tools/list, 라우팅)
                                │
              ┌─────────────────┼─────────────────────────┐
              │                 │                          │
   SimulationModelRegistry      │                McpToolRegistry
   (SimulationConfig 기반)      │           (독립 JSON 기반, 등록 0개)
              │                 │                          │
     JavaEngineProvider   PythonWasteSimAdapter    (미래: EdgeThrottlingModelAdapter 등)
     (SimulationEngine)   (ProcessBuilder → python)
              │                 │
     공통: SimulationConfigValidator        각 구현체가 자체 검증
     (모델과 무관하게 항상 먼저 검증)
```

## 6. 라즈베리파이 엣지 모델 연동 계획 (§3 McpToolProvider의 구체 적용 사례)

R&E 프로젝트(라즈베리파이 4/5 엣지 AI 발열·소비전력·추론성능 분석)에서 나올 모델·데이터를
이 시스템에 연결하는 방법을 제안받아 검토한 내용이다. 아래 3가지 방법 모두 아직 실제 모델이
없어 미착수 상태이며, §3의 `McpToolProvider` 확장점으로 구현하는 것을 전제로 정리했다.

### 6.1 엣지 카메라 비전 AI → DEVS 시뮬레이션 실측 보정

**필요한 데이터**

| 항목 | 설명 |
|---|---|
| 수거장 식별자 | 관측한 수거장이 시뮬레이션의 어느 `building index`(0..nB-1) 또는 `Node_A~D`에 대응하는지 매핑 정보. 카메라-건물 1:1 고정 배치를 전제로 최초 설치 시 한 번 등록 |
| 적재율/부피 추정치 | 0~1(또는 %) 범위의 fill ratio — `SimulationConfig.capacity`/`WasteType.threshold`와 같은 단위로 맞춰야 바로 결합 가능 |
| 관측 시각(timestamp) | 오래된 관측치를 신뢰하지 않기 위한 유효기간 판단에 필요 |
| 신뢰도(confidence) | YOLO 탐지 신뢰도 — 낮은 신뢰도 관측치를 걸러내는 임계값 판단용 |
| 디바이스 식별자·인증키 | 어떤 디바이스가 보냈는지, 위조 관측치로부터 시뮬레이션을 보호하는 최소 인증 수단 |

**구현해야 할 것**

- **라즈베리파이 쪽(이 저장소 밖)**: YOLO는 객체 "탐지"용이라 부피/적재율을 직접 재지는
  못한다 — 탐지 박스 수를 세거나 세그멘테이션 면적 비율로 근사하는 별도 후처리가 필요하다.
  카메라 각도·거리 고정(원근 왜곡 보정)도 필요. HTTP/WebSocket 클라이언트는 네트워크 단절 시
  로컬 버퍼링·재전송을 갖춰야 한다.
- **`waste-sim-spring` 쪽**: `McpToolProvider` 구현체(예: `report_bin_fill_level` 도구) +
  `EdgeObservationService`(신규, `TrafficDataService`와 같은 패턴으로 건물별 최신 관측치를
  메모리에 보관) + 자체 검증(fill ratio 범위, 신뢰도 임계값, 오래된 관측치 거부).
- **엔진 쪽 변경이 가장 큰 부분**: 지금 `SimulationEngine.run()`은 매번 `fill[][]`를 0에서
  시작한다. "실측으로 보정"하려면 (a) 관측된 현재 적재율을 초기 상태로 주입하는 옵션을
  추가하거나, (b) 고정 시각 수거 스케줄을 "적재율이 임계 이상이면 수거"하는 이벤트 기반
  동적 디스패치로 바꿔야 한다 — 후자는 `resolveVisitOrder`/`daySlots` 로직을 근본적으로
  다시 설계하는 큰 작업이라, "수거 차량 동선 재계산"까지 가려면 후자가 필요하다.

### 6.2 Thermal Throttling 상태 → 시뮬레이션 제어 신호

**필요한 데이터**: RTT(스로틀링까지 남은 시간), TTT(회복 예상 시간), 현재 FPS/클럭,
throttled 여부, 디바이스 식별자·timestamp.

**구현해야 할 것**

- `McpToolProvider` 구현체(예: `report_edge_thermal_status`) + `EdgeThermalStateService`(§6.1과
  같은 저장 패턴).
- `SimulationConfig`에 대응 필드가 전혀 없다 — "샘플링 주기를 낮춘다"를 표현하려면 신규
  필드(예: `edgeSamplingIntervalMinutes`)가 있어야 하고, 이건 §6.1이 먼저 구현돼 있어야
  의미가 생긴다(§6.1이 선행 조건).
- **정책 결정 로직은 반드시 결정론적이어야 한다.** 이 프로젝트의 핵심 원칙(C2: "실행/제어에
  영향을 주는 결정은 LLM-free·결정론적이어야 한다" — `ExecutionIntentDetector`,
  `TrafficKeywordDetector` 등 기존 코드 전체가 이 원칙을 지키려고 정규식 기반 판정으로
  LLM을 배제해 왔다)에 따라, "RTT < 5분이면 샘플링 주기를 2배로 늘린다" 같은 규칙은 LLM
  판단이 아니라 고정된 if-then 규칙(신규 `EdgeThermalPolicy` 클래스)으로 구현해야 한다.
- "연산 부하를 자바 백엔드로 Offloading"은 사실상 별개의 큰 작업이다 — 서버가 영상 스트림을
  받아 YOLO 추론을 대신 수행할 GPU/추론 인프라를 갖춰야 한다는 뜻이라, 이 DEVS 시뮬레이션
  서버의 역할 범위를 크게 벗어난다. 별개 프로젝트로 분리해 다루는 걸 권한다.

### 6.3 자연어 기반 라즈베리파이 제어 MCP 서버

**필요한 데이터**: 제어 가능 파라미터와 안전 범위(FPS 5~30, CPU 클럭 상한, 냉각팬 PWM
0~100% 등), 라즈베리파이 실제 제어 인터페이스(`cpufreq` sysfs, 팬 GPIO/PWM, 카메라 FPS API),
명령 실행 전후 상태 조회 결과.

**구현해야 할 것**

- **완전히 새로운 MCP 서버**(`raspberry-pi-mcp-server`, 신규 저장소) — 라즈베리파이 위에서
  직접 구동하며 `set_fps_limit`, `set_cooling_fan`, `set_cpu_clock_limit` 등을 노출한다.
  `McpToolCatalog`/`SimulationModelRegistry` 패턴(도구 하나 추가해도 나머지 코드는 안
  건드리는 구조)을 참고할 수 있다.
- 채팅 파이프라인 재사용: 기존 2단계 가드레일(시각 게이트 → 실행의도 → 파라미터 추출)을
  복제해 `EdgeControlIntentDetector`(신규, 결정론적 정규식 판정) → LLM은 "FPS=15, 팬=100%"
  같은 숫자 추출만 담당 → 검증기 통과 → 실행 순서로 설계하면 기존 원칙과 일관된다.
- 검증기(신규, fail-closed): "팬 0%인데 고온 지속" 같은 물리적으로 위험한 명령 조합 차단.
- **두 MCP 서버의 관계 결정 필요**: 시뮬레이션용(`waste-sim-spring`)과 엣지 제어용
  (`raspberry-pi-mcp-server`)을 한 채팅 세션에서 같이 쓰려면 클라이언트가 두 서버 모두에
  연결해 도구를 합쳐 봐야 한다(MCP 표준이 지원하는 멀티 서버 연결 — 프로토콜 차원엔 문제
  없음). 다만 지금 `ChatController`는 단일 `SimulationTool`만 알고 있어, "엣지 제어 명령"과
  "시뮬레이션 실행 명령"을 구분해 라우팅하는 로직이 추가로 필요하다.

### 6.4 통합 연구 주제화 (논문 프레이밍)

데이터·코드가 아니라 문서 작업이다 — 위 세 방법 중 적어도 하나가 실제로 동작해 결과 데이터
(발열-샘플링주기 상관관계, 실측 보정 전후 오차 감소량 등)를 내야 "하드웨어 계층 → 미들웨어
→ 시뮬레이션 계층" 3단 구조를 실증적으로 뒷받침할 수 있다. 프레이밍만 먼저 확정하고 결과가
없는 채로 투고하면 "구현 예정"에 머무는 제안서 수준이 되므로, §6.1(가장 구현 부담이 작고
독립적으로 결과를 낼 수 있음)을 먼저 완성해 실측 데이터를 확보하는 순서를 권한다.

### 6.5 프로젝트 진행 중 반드시 해야 할 것 (지금 안 하면 나중에 못 되돌리는 것들)

`waste-sim-spring` 쪽 코드(§6.1~6.3의 신규 도구·서비스·검증기)는 하드웨어 실험과 별개로
나중에 언제든 만들 수 있다. 반대로 아래 항목들은 R&E 실험을 실제로 돌리는 지금 시점에
정해두지 않으면, 실험이 다 끝난 뒤에는 데이터를 다시 모으는 것 말고는 되돌릴 방법이 없다.

- **데이터 스키마·단위를 지금 확정한다**: 적재율은 원시값(박스 수·면적)과 정규화값(0~1)을
  둘 다 로그에 남긴다. 건물/수거장 식별자는 처음부터 `Node_A`~`Node_D` 라벨을 붙인다. 모든
  로그의 시각은 NTP 동기화된 하나의 절대시각(ISO 8601)으로 통일한다. 디바이스/실험(run) ID를
  보드·냉각조건·모델버전(FP32/INT8)별로 매겨 메타데이터로 남긴다.
- **"동시 관측 세트"를 최소 한 번은 확보한다**: 같은 시간대에 (a) 카메라로 찍은 실제 적재
  상태와 (b) 수동으로 돌려본 시뮬레이션 예측값을 나란히 기록해두면, 나중에 "보정 전후 오차가
  얼마나 줄었는지"를 계산할 근거가 생긴다.
- **정책 규칙의 근거 데이터를 의도적으로 모은다**: 스로틀링 발생 시점과 그 직전 온도·클럭
  변화, 냉각 조건별 안전/불안전 경계 사례(이게 그대로 §6.3 검증기의 허용 범위가 된다),
  스로틀링 시 FPS가 실제로 몇 % 떨어졌는지(수치로).
- **통신 프로토타입을 실험 초반에 아주 가볍게라도 검증한다**: 라즈베리파이에서 서버(또는
  로컬 mock)로 HTTP POST 한 번 왕복시켜본다 — 프로젝트 막바지에 처음 시도하면 네트워크
  지연·페이로드·인증 문제가 나와도 재실험할 시간이 없다.
- **인증 체계는 처음부터 최소한이라도 넣는다**: 디바이스마다 고정 API 키 하나씩 요청
  헤더에 싣는 습관을 들이면, 나중에 검증기를 붙일 때 별도 재작업이 필요 없다.

### 6.6 권장 착수 순서

1. §6.1의 관측 수신 파이프라인만 먼저 구현 — `report_bin_fill_level` 도구 + `EdgeObservationService`
   + 검증기. 엔진의 초기상태 주입까지는 가지 않고, 우선 "실측 적재율을 받아서 저장·조회할
   수 있다"는 최소 기능부터 검증한다.
2. 라즈베리파이 R&E 실험(발열·FPS 측정)이 어느 정도 진행되면 §6.2의
   `report_edge_thermal_status`를 같은 패턴으로 추가한다 — §6.1과 데이터 흐름이 거의 동일해
   재사용이 크다.
3. §6.3(자율 제어)은 안전 검증기 설계가 까다롭고 별도 서버 구축이 필요해 가장 나중으로
   미루는 걸 권한다 — 물리적 장치(팬, 클럭)를 잘못 제어하면 실제 하드웨어 손상 위험이 있어
   훨씬 보수적인 검증이 필요하다.

## 7. 참고 — 왜 별도 MCP 서버로 완전히 쪼개지 않았나

대안으로 "waste_sim(Python 엔진)을 완전히 독립된 별도 MCP 서버로 띄우고, MCP 클라이언트가
Java/Python 두 서버에 각각 접속"하는 방식도 가능했다. 이 방식은 두 시스템을 더 느슨하게
분리하지만, 지금 채팅 UI가 이미 하나의 MCP 엔드포인트(`/mcp`)만 바라보도록 만들어져 있어
서버를 두 개로 나누면 채팅 UI·검증 파이프라인·메트릭이 전부 이중화된다. 지금 규모에서는
"같은 서버 안에서 모델만 갈아끼우는" 어댑터 방식(§2)이 더 적은 변경으로 목표를 달성했다.
독립 도구(§3)도 같은 이유로 별도 서버 대신 같은 MCP 서버 안의 다른 확장점으로 만들었다 —
다만 §6.3(라즈베리파이 물리 제어)처럼 아예 다른 하드웨어 위에서 도는 도구는 별도 프로세스
(별도 MCP 서버)가 자연스러운 예외다. 향후 모델 수가 많아지고 각각 독립 배포·스케일링이
필요해지면 그때 서버 분리를 재검토할 수 있다.
