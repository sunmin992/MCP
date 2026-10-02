# 브로커·시뮬레이터 MCP 분리와 실제 모델로 드러난 버그 셋 — 실행 기록

2026-09-28 · 시작 커밋 `bd61010` → 끝 커밋 `26c76b7` (`feature/simulator-mcp`) · 전체 테스트 572개 중 569개 통과(스킵 3) · 포트 8089(브로커) · 8090(시뮬레이터)

전날 [`collection-time-sweep-2026-09-27`](../collection-time-sweep-2026-09-27/REPORT.md) 은 요청을
Python 스크립트로 보냈다. 오늘은 **실제 모델(Claude Code 세션)을 MCP 클라이언트로 붙여** 사용자가
직접 채팅으로 요청했고, 스크립트로는 한 번도 걸리지 않던 버그 둘이 나왔다. 그 뒤 명세 §1 과
그림 2 대로 **브로커 MCP 서버와 장량동 시뮬레이터 MCP 서버를 별개 프로세스로 나눴고**, 나눈
구조로 0~14단계를 끝까지 돌리다 버그 하나를 더 찾았다.

## 이 기록의 성격

서버에 실제 JSON-RPC 요청을 보내고 받은 응답을 그대로 파일로 남겼다. 각 `*.json` 은
`{요청, 응답}` 한 쌍이며 손대지 않았다. 12단계 확인은 사람이 브라우저에서 직접 눌렀다.

| 파일 | 내용 |
|---|---|
| [`0-registration-log.txt`](0-registration-log.txt) | 0단계 등록 — 두 서버 로그에서 등록 줄만 |
| [`1-split-steps-3-5/`](1-split-steps-3-5/) | 분리 직후 3·4단계(브로커) → 5단계(시뮬레이터) |
| [`2-e2e-steps-0-14/`](2-e2e-steps-0-14/) | 분리한 구조로 0~14단계 끝까지. 6단계 버그가 담겨 있다 |
| [`3-e2e-after-planner-fix/`](3-e2e-after-planner-fix/) | 6단계 수정 뒤 3~10단계 |

---

## 1. 실제 모델이 드러낸 버그 둘

### 정수 150 을 받지 못했다 → `67d7e78`

채팅 세션의 모델이 `routeAvailableCapacityKg` 를 `150` 으로 보내자 `build_scenario` 가
`argument type mismatch` 로 실패했다. 같은 요청을 직접 보내 재현했다.

| 보낸 값 | 결과 |
|---|---|
| `150` | `EXECUTION_ERROR` · `argument type mismatch` |
| `150.0` | 시나리오 생성 |

Jackson 은 소수점 없는 `150` 을 `Integer` 로 읽는다. `PesFlattener.findSetter` 는 `Double` 세터에
아무 `Number` 나 받아 주지만 `invoke` 에 `Integer` 를 그대로 넘겼다. 리플렉션은 `Integer` 를 기본형
`double` 로는 넓혀 주지만 박싱된 `Double` 로는 바꾸지 않는다. `SimulationConfig` 에서 박싱 `Double`
세터는 `setRouteAvailableCapacityKg` 하나라 이 값에서만 터졌다.

**전날까지 드러나지 않은 이유** — 실행 기록은 Python 으로 보냈고, Python 은 `150.0` 을 실수 그대로
보낸다. 모델 쪽에서 우회할 길도 없었다 — `"150"` 은 세터를 못 찾고, `150.5` 는 승인하지 않은 값이다.

### 모델이 8080 을 안내했다 → `603c284`

채팅 세션이 사용자에게 `http://localhost:8080/confirm.html` 을 열라고 안내했고, 서버는 8090 에서
듣고 있어 확인 화면이 열리지 않았다. 확인을 못 하면 아무것도 실행되지 않는다. 원인은 둘이었다.

- 능력 카드의 `endpoint` 가 `http://localhost:8080/mcp` 였다. `server.port` 는 8090 이다.
- `get_scenario_status` 가 `confirmUrl` 을 `"/confirm.html"` 경로만으로 냈다. 모델은 호스트와 포트를
  짐작해 붙이게 되고, 카드의 8080 을 가져다 썼다.

카드를 고치고, `confirmUrl` 을 `server.port` 로 만든 전체 주소로 바꿨다. 카드 주소가
`application.properties` 의 포트와 어긋나면 실패하는 시험을 넣었다.

---

## 2. 두 서버로 나눔 → `e260cbe`

명세 §1 은 두 서버를 따로 둔다 — 브로커는 등록된 시뮬레이터의 목록(기능 · 적용 범위 · 연결
정보)만 들고 3·4단계에 답하고, 시뮬레이터는 0단계에 자기 정보를 브로커에 등록한 뒤 5~14단계를
받는다. 지금까지는 둘이 `/mcp` 하나에 합쳐져 있어서 0단계 등록이 "자기 카드를 읽는 코드" 로 접혀
있었고, 브로커가 시뮬레이터의 클래스를 직접 볼 수 있어 카드만 보고 고른다는 경계가 지켜지는지
확인할 방법이 없었다.

| 모듈 | 서버 | 주소 | 도구 |
|---|---|---|---|
| `broker` | 브로커 MCP | `http://localhost:8089/mcp` | `find_simulators` · `list_candidates` |
| `simulator` | 장량동 시뮬레이터 MCP | `http://localhost:8090/mcp` | 템플릿 · 서브태스크 · 시나리오 · 실행 12개. 확인 화면 `/` |
| `mcp-common` | — | — | 도구 계약 5개(`McpToolProvider` · `ToolResult` 등) |

`broker` 는 `simulator` 에 의존하지 않는다.

**정한 것**

- **등록은 MCP 도구가 아니다.** `POST /api/simulators` 로 받는다. 도구로 내면 LLM 이 카드를 지어
  등록하고 스스로 고를 수 있다.
- **시뮬레이터는 뜰 때와 30초마다 등록한다**(`BrokerRegistrar`). 보내는 것은 `get_capability` 가
  내는 카드 원문 그대로다. 브로커가 없어도 시뮬레이터는 뜨고, 로그는 성공/실패가 바뀔 때만 남긴다.
- **재등록은 새 카드로 바꾸고, 지어낸 후보 파일의 id 는 등록으로 덮지 못한다.**
- **매칭 결과에 `endpoint` 를 싣는다.** 그림 4단계의 "연결 정보" 인데 지금까지 빠져 있어서, LLM 이
  브로커의 답만으로는 고른 서버로 갈 수 없었다.
- **브로커도 루프백에만 바인딩한다.** 인증이 없어 누구나 카드를 등록할 수 있기 때문이다.
- 두 서버를 한 JVM 에서 잇던 `EndToEndBrokerFlowTest` 는 둘 수 있는 모듈이 없어 브로커 쪽(3·4단계 +
  연결 정보)으로 줄였다. 잇는 부분은 아래처럼 실제 두 프로세스로 확인했다.

**실측** — [`0-registration-log.txt`](0-registration-log.txt) · [`1-split-steps-3-5/`](1-split-steps-3-5/)

| 확인 | 결과 |
|---|---|
| 브로커만 떴을 때 목록 | `district-waste-sim` · `water-leak-sim` (지어낸 후보 둘) |
| 시뮬레이터가 뜬 직후 | 셋. 기동 완료 0.1초 안에 등록(14:55:02.283 기동 → .345 브로커 수신) |
| 4단계 매칭 | `jangnyang-waste-sim` 4점 · `endpoint` = `http://localhost:8090/mcp` |
| 그 endpoint 로 5단계 | 템플릿 14개 |
| 시뮬레이터 `tools/list` 에 브로커 도구 | 없음 |
| 브로커만 다시 띄움 | 목록에서 장량동이 빠졌다가 **11초 뒤** 재등록(14:55:51 기동 → 14:56:02 등록) |

함께 고친 것: 작업 폴더가 모듈로 바뀌어 저장소 루트 파일을 읽던 시험 다섯의 경로, 전처리 스크립트의
기본 출력 경로(루트에서 돌리면 없는 폴더에 쓰고 있었는데, 시험은 모듈 폴더 기준으로 보느라 통과하고
있었다), `.mcp.json` 에 서버 둘, README 실행법.

실행도 바꿨다 — jar 없이 루트에서 `-pl broker -am spring-boot:run` · `-pl simulator -am
spring-boot:run`. jar 를 쓰던 동안 서버가 돌고 있는 채로 다시 빌드하다 Windows 파일 잠금으로
repackage 가 실패하고 jar 가 얇은 jar 로 덮인 일이 있었다. `spring-boot:run` 은 클래스 폴더로 뜨므로
그 문제가 없다.

---

## 3. 나눈 구조로 끝까지 — 0~14단계

[`2-e2e-steps-0-14/`](2-e2e-steps-0-14/) · 요청 조건은 전날과 같다(4동 × 25세대 · 30일 · 1톤 3대 ·
15분 간격 · 평일 교통 · 00~23시 24구간 × 30회). `routeAvailableCapacityKg` 는 **정수 150** 으로 보냈다.

| 단계 | 서버 | 결과 |
|---|---|---|
| 0 | 시뮬레이터 → 브로커 | 등록 |
| 3·4 | 브로커 | 장량동 4점 1위 · 연결 정보 `http://localhost:8090/mcp` |
| 5 | 시뮬레이터 | 템플릿 14개 |
| 6 | 시뮬레이터 | `FILLED 12 · DEFERRED 1 · NOT_GENERATED 1` · **`complete=false`** ← 아래 |
| 8 | 시뮬레이터 | 거절 없음 — 정수 150 이 통과했다(`67d7e78` 확인) |
| 9·10 | 시뮬레이터 | `scn-a366a1b1` · 24벌 · 미승인 3(`seeds` · `routeAvailableCapacityKg` · `travelTimeMode`) |
| 10 상태 | 시뮬레이터 | `nextStep` 에 `http://localhost:8090/confirm.html` 전체 주소(`603c284` 확인) |
| 12 | 확인 화면 | 사람이 눌렀다 → `CONFIRMED` |
| 13·14 | 시뮬레이터 | `EXECUTED` |

**결과는 전날 단일 서버 실행과 `allTotals` 까지 한 자리도 다르지 않다.** 서버를 나누는 동안 계산이
바뀌지 않았다는 확인이다. 결론도 같다 — 07~09시와 13~14시는 피할 것, 11·12시는 00시와 구별되지
않는다. 확인 토큰도 전날과 같은 `cft-0c98458488ca4afc` 가 나왔다. 토큰은 설정의 해시이고 설정이
같으므로 맞다.

### 6단계가 끝나지 않았다 → `5f06d42`

구간 상수(`LEGACY_CONSTANT`)로 필요한 값을 전부 채웠는데 `complete=false` 였다.

```
NOT_GENERATED  zoneAssignmentRule       ← 교통구역 근사 · 5동 이상일 때만 생성된다
DEFERRED       intraZoneTravelMinutes   ← 그 규칙이 CONTIGUOUS 일 때만 생성된다
```

`CONTIGUOUS_ZONE_RULE` 은 규칙 값이 없으면 무조건 `UNKNOWN` 을 냈다. 규칙을 묻지 않는 설정에서 규칙
값이 없는 것은 "아직 모름" 이 아니라 "영영 없음" 인데 보류로 남았다. 그러면 LLM 은 오지 않을 질문을
기다리거나 필요 없는 값을 사용자에게 묻는다. 시나리오 생성은 막히지 않아서 조용히 지나갔다.

**전날 드러나지 않은 이유** — 생성되지 않는 규칙 값(`ROUND_ROBIN`)을 함께 보냈다. 실제 모델은
필요 없는 값을 보내지 않는다. 버그 셋 가운데 둘이 같은 모양이다 — **스크립트가 보내는 모양과 모델이
보내는 모양이 달랐다.**

규칙 자체가 생성되지 않으면 이 조건도 `INACTIVE` 로 판정한다. 규칙이 걸리는데 답이 없을 때는
지금처럼 `UNKNOWN` 이다. 수정 뒤 같은 요청 — [`3-e2e-after-planner-fix/06-plan-subtasks.json`](3-e2e-after-planner-fix/06-plan-subtasks.json):

```
FILLED 12 · UNFILLED 0 · DEFERRED 0 · NOT_GENERATED 2 · complete=true
```

---

## 4. 남은 것

- **확인 API 에 인증이 없다.** `POST /api/scenarios/{id}/confirm` 은 셸을 쓸 수 있는 모델이 `curl`
  로 부를 수 있다. "확인은 사람만" 이 코드가 아니라 모델의 자제에 기대고 있다. 오늘 채팅 시험은
  세션 시작 때 "localhost:8090 에 직접 요청하지 마" 라고 말해 두는 것으로 막았다.
- **출처는 모델이 스스로 적는다.** 모델이 제안한 값을 `USER` 로 적으면 "승인하지 않은 기본값"
  경고가 뜨지 않는다. 채팅 시험의 시나리오는 미승인 0개였다 — 사용자가 동의했다면 맞다.
- **기존 실행 도구는 토큰을 소모하지 않는다**(전날 기록과 같다).
- **시뮬레이터의 `spring.application.name` 이 아직 `waste-sim-spring` 이다.** 로그에서 브로커
  (`waste-sim-broker`)와 구별은 되지만 모듈 이름(`waste-sim-simulator`)과 다르다.
- **[`MCP_MODEL_INTEGRATION.md`](../../guides/MCP_MODEL_INTEGRATION.md) §1 은 아직 엔드포인트 하나를
  전제로 적혀 있다.** 코드 경로 한 줄만 고쳤다.
- **`.vscode/tasks.json` 은 `.gitignore` 에 걸려 커밋되지 않았다.** `Ctrl+Shift+B` 로 두 서버를 띄우는
  설정이 이 PC 에만 있다.

## 재현 방법

루트에서 창 두 개로 띄운다. 시뮬레이터가 뜨면 브로커에 스스로 등록한다.

```bash
./mvnw.cmd -q -pl broker -am spring-boot:run
./mvnw.cmd -q -pl simulator -am spring-boot:run
```

`1-` · `2-` · `3-` 폴더의 `*.json` 은 `b*`/`03-*` 을 `http://localhost:8089/mcp` 에, 나머지를
`http://localhost:8090/mcp` 에 UTF-8 로 보낸다. 12단계는 `http://localhost:8090/` 에서 사람이 누른다.
MCP 클라이언트로 붙이려면 이 폴더의 `.mcp.json` 을 쓴다 — 서버 둘을 따로 잡는다.
