# waste_sim(Python) 모델을 MCP 도구로 연결하고, 향후 새 모델을 계속 추가할 수 있게 하는 방법

대상 시스템: `waste-sim-spring` (기존 MCP 서버 보유)
목표: (1) 원본 Python DEVS 모델(`waste_sim`, pyevsim 기반)을 지금의 MCP 서버에
도구로 연결해 시뮬레이션을 생성할 수 있게 하고, (2) 앞으로 어떤 새 모델이
와도 같은 방식으로 쉽게 추가할 수 있는 구조를 만든다.

---

## 1. 현재 구조 진단

`waste-sim-spring`은 이미 완전한 MCP 서버를 갖고 있다.

- `McpController` — `POST /mcp`, JSON-RPC 2.0 (`initialize`/`tools/list`/`tools/call`)
- `McpToolCatalog` — 도구 목록·JSON Schema 정의 (`run_waste_simulation`, `run_scenario`, `list_scenarios`, `update_route_sequence`)
- `SimulationTool` — 검증(`SimulationConfigValidator`) → 실행을 캡슐화하는 파사드. MCP·REST·채팅 세 진입점이 전부 이 한 곳만 호출한다.
- `SimulationEngine` — 실제 계산을 수행하는 **Java로 재구현한 이벤트 큐 엔진**.

즉 지금 `run_waste_simulation` 도구를 호출하면 **Java 엔진**이 도는 것이고,
원본 `waste_sim`(Python, pyevsim DEVS 원자모델)은 이 MCP 서버와 전혀 연결돼
있지 않다. 두 모델은 같은 논문에 뿌리를 두고 있지만 엔진도, 코드베이스도,
실행 환경(JVM vs Python)도 다르다.

---

## 2. 설계 원칙 — "모델 어댑터" 패턴

새 모델을 추가할 때마다 `McpController`의 분기(switch)를 계속 늘리는 방식은
금방 지저분해진다. 대신 **공통 인터페이스 하나를 정의하고, 모델마다 그
인터페이스의 구현체(어댑터)만 추가**하는 구조로 간다. `McpToolCatalog`는
등록된 어댑터 목록을 순회하며 자동으로 도구를 노출한다.

```java
// com.wastesim.mcp.SimulationModelProvider
public interface SimulationModelProvider {
    String modelId();          // 예: "java-devs", "python-devs"
    String toolName();         // 예: "run_waste_simulation", "run_waste_simulation_devs"
    String description();      // MCP tools/list에 노출될 설명
    String inputSchemaJson();  // JSON Schema 문자열
    ToolResult run(JsonNode args) throws Exception;
}
```

- 기존 Java 엔진도 이 인터페이스의 구현체(`JavaEngineProvider`)로 감싼다 — 즉
  리팩터링 후에는 지금의 `run_waste_simulation`도 "여러 모델 중 하나"가 된다.
- `McpToolCatalog`는 `List<SimulationModelProvider>`를 스프링이 자동
  주입하도록 받아, `tools/list` 응답과 `tools/call` 라우팅을 모두 이 목록
  기반으로 만든다. **새 모델을 추가해도 `McpController`/`McpToolCatalog`
  자체는 코드 변경이 필요 없다** (Open/Closed 원칙).

---

## 3. Part A — waste_sim(Python) 연결 구체 절차

### 3.1 Python 쪽: 프로그래밍적 호출용 진입점 추가

지금 `run.py`는 CSV·PNG 파일을 저장하는 실험 스크립트라서, MCP 도구 호출처럼
"입력 JSON → 출력 JSON" 한 번으로 끝나는 용도에는 맞지 않는다. 사이드이펙트
없는 얇은 진입점을 하나 추가한다.

`waste_sim/mcp_bridge.py` (신규):
```python
# stdin으로 JSON 설정을 받아 build_and_run()을 그대로 재사용하고
# stdout에 JSON 결과 한 줄만 출력한다 (CSV/PNG 저장 없음).
import sys, json
from .run import build_and_run
from .occupations import hms_to_minutes

def main():
    cfg = json.load(sys.stdin)
    h, m = map(int, cfg["collectionTime"].split(":"))
    result = build_and_run(
        collection_time=hms_to_minutes(h, m),
        seed=cfg.get("seed", 1),
        days=cfg.get("days", 30),
        n_buildings=cfg.get("numBuildings", 4),
        residents_per_building=cfg.get("residentsPerBuilding", 25),
        occupation_mix=cfg.get("occupationMix"),
        leave_sigma=cfg.get("leaveSigma", 30.0),
        waste_sigma=cfg.get("wasteSigma", 0.3),
        capacity=cfg.get("capacity", 30.0),
        cleanliness_threshold=cfg.get("threshold", 0.8),
    )
    print(json.dumps(result))

if __name__ == "__main__":
    main()
```
여러 시드가 필요하면(현재 `run_waste_simulation`처럼) 이 진입점을 시드 수만큼
반복 호출하거나, `mcp_bridge.py` 안에서 `seeds` 배열을 받아 루프를 돌리고
평균/표준편차까지 계산해 반환하도록 확장한다(권장 — Java 쪽 호출을 한 번으로
줄일 수 있다).

### 3.2 Java 쪽: PythonWasteSimAdapter 작성

`ProcessBuilder`로 `python3 -m waste_sim.mcp_bridge`를 서브프로세스로 실행하고,
JSON을 stdin에 써준 뒤 stdout을 읽어 파싱한다.

```java
@Component
public class PythonWasteSimAdapter implements SimulationModelProvider {

    @Override public String modelId() { return "python-devs"; }
    @Override public String toolName() { return "run_waste_simulation_devs"; }
    @Override public String description() {
        return "원본 논문 재현 Python/pyevsim DEVS 엔진으로 시뮬레이션을 실행한다 "
             + "(Java 엔진과 결과 비교용 참조 구현).";
    }
    @Override public String inputSchemaJson() { return RUN_SIM_SCHEMA; } // McpToolCatalog와 동일 스키마 재사용 가능

    @Override
    public ToolResult run(JsonNode args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "python3", "-m", "waste_sim.mcp_bridge")
                .directory(new File(pythonProjectRoot))   // adev-master 상위 폴더
                .redirectErrorStream(false);
        Process p = pb.start();
        try (var out = p.getOutputStream()) {
            out.write(args.toString().getBytes(StandardCharsets.UTF_8));
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            return ToolResult.rejected(new ValidationError(
                    ErrorCode.EXECUTION_ERROR, "python-devs", "실행 실패: " + stderr));
        }
        JsonNode result = new ObjectMapper().readTree(stdout);
        return ToolResult.ok(result);   // 필요하면 SimulationResult로 재매핑
    }
}
```

핵심 설계 포인트:
- **기존 `SimulationConfigValidator`를 그대로 재사용**한다 (범위 밖 값 등은
  Python을 부르기 전에 Java 쪽에서 먼저 걸러낸다) — `SimulationTool`이 아니라
  이 어댑터가 직접 검증을 부르거나, `SimulationTool`에 "모델 선택" 매개변수를
  추가해 기존 파사드 경로를 그대로 태운다(권장, 아래 3.4).
- Python 프로세스 실행 파일 경로(`python3`)와 작업 디렉터리는
  `application.properties`에 설정값으로 뺀다 (`waste-sim.python.executable`,
  `waste-sim.python.project-root`).
- 타임아웃(위 예시 30초)을 반드시 둔다 — 프로세스가 멈추면 MCP 응답 자체가
  막히므로.

### 3.3 새 MCP 도구로 등록

`McpToolCatalog`가 `List<SimulationModelProvider>`를 순회하도록 리팩터링하면,
`PythonWasteSimAdapter`를 스프링 `@Component`로 등록하는 것만으로 `tools/list`
응답에 `run_waste_simulation_devs`가 자동으로 나타난다. 채팅 UI에서도
"파이썬 엔진으로 돌려줘" 같은 요청을 이 도구로 라우팅하도록
`ExecutionIntentDetector`/`OpenAiService` 쪽에 도구 이름 매핑만 추가하면 된다.

### 3.4 검증 파이프라인 공유 (권장 설계)

`SimulationTool`에 모델 선택을 얹는 방식이 가장 깔끔하다.

```java
public ToolResult runSimulation(SimulationConfig cfg, String modelId, boolean skipWarnings) {
    ValidationResult vr = validator.validate(cfg);   // 모델과 무관하게 항상 먼저 검증
    if (!vr.ready()) return ToolResult.rejected(vr.errors());
    SimulationModelProvider model = registry.get(modelId);   // 없으면 기본값 "java-devs"
    return model.run(cfg);
}
```
이렇게 하면 Java 엔진이든 Python 엔진이든 **같은 검증 규칙, 같은 오류 코드,
같은 채팅/REST/MCP 진입점**을 공유하게 되어 "엔진만 다르고 나머지는 동일한
경험"이 보장된다.

### 3.5 검증(비교 테스트)

같은 조건(수거 시각·seed·직업 구성 등)으로 `run_waste_simulation`(Java)과
`run_waste_simulation_devs`(Python) 결과를 나란히 호출해, 두 엔진이 같은 논문
모델을 정확히 재현하고 있는지 회귀 테스트로 고정한다. 완전히 같은 난수
알고리즘이 아니므로 값이 100% 일치하진 않겠지만, 평균 민원 수의 경향(예:
12시 수거가 최소)은 같아야 한다.

---

## 4. Part B — 앞으로 새 모델을 추가하는 표준 절차

이 구조가 갖춰지면, 새로운 시뮬레이션 모델(예: 다른 지역 모델, 다른 언어로
구현된 엔진, 강화학습 기반 정책 모델 등)을 추가하는 절차는 항상 다음
체크리스트로 고정된다.

1. **모델을 호출 가능한 형태로 준비** — 이미 어떤 언어로든 상관없다.
   프로그램으로 "설정 JSON을 주면 결과 JSON을 돌려주는" 진입점만 있으면 된다
   (동일 프로세스 라이브러리든, 서브프로세스든, 별도 HTTP 서비스든 무방).
2. **`SimulationModelProvider` 구현체 작성** — `modelId`/`toolName`/
   `description`/`inputSchemaJson`/`run()` 다섯 개만 채우면 끝.
3. **`@Component`로 등록** — 스프링이 자동으로 `McpToolCatalog`의 목록에
   포함시킨다. `McpController`/`McpToolCatalog` 자체는 손댈 필요 없음.
4. **입력 스키마 정의** — 기존 `run_waste_simulation` 스키마를 재사용하거나
   모델 고유 파라미터가 있으면 그 부분만 확장.
5. **검증 규칙 재사용 또는 확장** — 공통 범위(예: threshold 0~1)는
   `SimulationConfigValidator`를 그대로 타고, 모델 전용 규칙이 있으면 어댑터
   내부에서 추가 검증 후 `ValidationError`로 반환.
6. **테스트 추가** — 어댑터 단위 테스트(정상/오류/타임아웃) + 필요하면 기존
   모델과의 비교 회귀 테스트.
7. **채팅 라우팅(선택)** — 사용자가 자연어로 "이 모델로 실행해줘"라고 했을
   때 어떤 도구로 갈지 `ExecutionIntentDetector` 쪽에 한 줄 추가.

이 절차를 따르면 모델이 몇 개로 늘어나도 MCP 서버의 핵심 코드(`McpController`)
는 전혀 변경되지 않고, 새 모델마다 어댑터 클래스 하나 + 등록 한 줄만
늘어난다.

---

## 5. 아키텍처 요약도 (텍스트)

```
                         POST /mcp (JSON-RPC)
                                │
                        McpController
                                │
                    McpToolCatalog (tools/list, 라우팅)
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
     JavaEngineProvider   PythonWasteSimAdapter   (미래 모델 Provider...)
     (SimulationEngine)   (ProcessBuilder → python3 -m waste_sim.mcp_bridge)
              │                 │
     공통: SimulationConfigValidator (모델과 무관하게 항상 먼저 검증)
```

---

## 6. 우선순위 제안

| 순서 | 작업 | 비고 | 상태 |
|---|---|---|---|
| 1 | `SimulationModelProvider` 인터페이스 도입 + 기존 Java 엔진을 `JavaEngineProvider`로 감싸기 | 리팩터링만, 동작 변화 없음 | **완료** |
| 2 | `waste_sim/mcp_bridge.py` 작성 | Python 쪽 최소 변경(§3.1 권장대로 seeds 배열 반복+집계까지 포함) | **완료** |
| 3 | `PythonWasteSimAdapter` 작성 + 등록 | 새 도구 `run_waste_simulation_devs` 노출 | **완료** |
| 4 | 비교 검증 테스트 작성 | 두 엔진이 같은 모델을 재현하는지 확인 | **완료**(아래 참고) |
| 5 | 채팅 라우팅 연결(선택) | "파이썬 엔진으로" 같은 자연어 요청 지원 | 미착수(선택 사항) |

1~3번까지 하면 원본 요청("waste_sim을 MCP 도구로 연결")이 충족되고, 이 구조
자체가 곧 "새 모델을 계속 추가할 수 있는 방법"(Part B)이 된다.

### 구현 결과 요약

- `com.wastesim.mcp.SimulationModelProvider`(인터페이스) · `SimulationModelRegistry`
  (스프링이 `List<SimulationModelProvider>`를 자동 주입) · `JavaEngineProvider`
  (기존 엔진, modelId=`java-devs`) · `PythonWasteSimAdapter`(신규, modelId=
  `python-devs`)를 추가했다.
- `SimulationTool.runSimulation(cfg, modelId, skipWarnings)`를 새로 추가해
  §3.4 권장 설계(검증은 항상 공통, 실행만 모델별로 위임)를 그대로 반영했다.
  기존 2-인자/1-인자 오버로드는 `java-devs`를 기본값으로 그대로 호출해
  하위호환을 유지한다(동작 변화 없음).
- `McpToolCatalog.toolsList()`/`McpController.callTool()`을 레지스트리 순회
  방식으로 바꿔, 새 모델을 등록만 하면 `tools/list`·`tools/call` 라우팅에
  자동으로 나타난다(코드 변경 불필요 — Open/Closed 원칙 실증).
- `waste_sim/mcp_bridge.py`(adev-master)는 §3.1의 권장대로 `seeds`를 반복
  실행해 평균·표준편차·직업별 평균(0건인 직업군도 항목 유지 — 
  waste-sim-spring DESIGN_DECISIONS.md D-11과 동일 원칙)까지 집계해 반환한다.
- 검증(§3.5): `PythonWasteSimAdapterTest`(단위) + 라이브 MCP 호출로 같은
  설정(12:00, 10일×10시드)을 두 엔진에 나란히 호출해 확인 — Java 6.9±4.3건,
  Python 7.4±3.0건으로 완전히 같지는 않지만(난수 알고리즘이 다름) 같은
  경향(학생 직업군이 지배적, 생산직·주부는 0건)을 보였다. 공통 검증 게이트
  (`days=0` 등 범위 밖 값)가 두 도구 모두 동일하게 차단하는 것도 확인했다.
- 환경별 설정: `application.properties`의 `waste-sim.python.executable`
  (기본 `python`, 필요시 `WASTE_SIM_PYTHON_EXECUTABLE`로 `python3` 등으로
  재정의)과 `waste-sim.python.project-root`(기본값은 이 개발 머신의
  `adev-master` 절대경로 — **다른 환경에서는 `WASTE_SIM_PYTHON_PROJECT_ROOT`로
  반드시 재설정**)로 뺐다.

---

## 7. 참고 — 왜 "Python을 자바 프로세스로 감싸는" 방식을 택했나

대안으로 "waste_sim을 완전히 독립된 별도 MCP 서버로 띄우고, Claude 같은 MCP
클라이언트가 두 서버(Java/Python)에 각각 접속"하는 방식도 가능하다. 이
방식은 두 시스템을 더 느슨하게 분리할 수 있지만, 지금 채팅 UI가 이미 하나의
MCP 엔드포인트(`/mcp`)만 바라보도록 만들어져 있어 서버를 두 개로 나누면
채팅 UI·검증 파이프라인·메트릭이 전부 이중화된다. 지금 규모에서는 위에서
설명한 "같은 서버 안에서 모델만 갈아끼우는" 어댑터 방식이 더 적은 변경으로
목표를 달성한다. 향후 모델 수가 많아지고 각각 독립 배포·스케일링이
필요해지면 그때 별도 MCP 서버 분리를 재검토할 수 있다.
