# 사용자 요청 처리 과정 실행 기록 — 요청에서 실행까지

2026-09-27 · 기준 커밋 `ed5c323` (`feature/simulator-mcp`) · 전체 테스트 554개 초록 · 포트 8090

챗 UI 와 LLM 자바 경로를 걷어낸 뒤, **MCP 도구와 확인 화면만으로** 요청을 끝까지 처리한
기록이다. 명세 §3 의 3~14단계를 한 번의 실행으로 통과시켰다.

## 이 기록의 성격

`java -jar` 로 띄운 서버에 **실제 JSON-RPC 요청을 보내고 받은 응답을 그대로** 파일로 남겼다.
각 `NN-*.json` 은 `{요청, 응답}` 한 쌍이며 손대지 않았다.

12단계(사용자 확인)만은 HTTP 화면이라, 브라우저로 직접 눌렀고 그때의 DOM 상태를 앱의 CSS와
함께 굳혀 HTML 두 장으로 남겼다. 값을 서버에 직접 밀어 넣거나 검증을 우회한 곳은 없다.

**PNG 는 만들지 않았다.** 이 세션의 도구로는 브라우저 화면을 파일로 저장할 수 없어서, 앞선
`ledger-gate-capture-2026-09-14`·`llm-blueprint-capture-2026-09-06` 과 같이 HTML 만 남긴다.
각 파일은 CSS 를 인라인한 자체 완결형이라 브라우저로 바로 열면 된다.

## 실행 환경

| | |
|---|---|
| 기동 | `java -jar target/waste-sim-spring-1.1.1.jar` |
| 기동 시간 | 3.32초 |
| 교통 구역 | 4개 등록 |
| 구역 간 자유주행시간 | 12쌍 등록 |
| 수거 지점 좌표 | **0곳** — 그래서 `OSRM_HYBRID` 는 쓸 수 없고 이번 실행은 `LEGACY_CONSTANT` 다 |
| LLM | **쓰지 않음** — 자바 LLM 경로를 `7a767f5` 에서 지웠다. 요청 프로필은 이 기록이 직접 넣었다 |

---

## 0. 전체 구조 — 문이 둘이다

```
LLM  ──JSON-RPC──>  POST /mcp  ──>  McpController.callTool
                                          │
                        ┌─────────────────┼─────────────────┐
                   브로커 도구 2      SES 도구 7        기존 도구 5
                                          │
사람 ──브라우저──>  GET / → /confirm.html  ──>  ScenarioConfirmController
```

**MCP 진입점은 `/mcp` 하나**다([`McpController.java:58`](../../../src/main/java/com/wastesim/mcp/McpController.java)).
`initialize` · `ping` · `tools/list` · `tools/call` 네 메서드만 받는다.

**확인만 다른 문으로 들어온다.** 사람이 브라우저로 직접 누르며, LLM 을 거치지 않는다.
거치게 두면 모델이 스스로 동의를 만들어낼 수 있다.

---

## 3단계 — 어느 서버가 이 요청을 받을 수 있나

[`03-find-simulators.json`](03-find-simulators.json)

요청은 발화가 아니라 **구조**다. 서버가 자연어를 해석하지 않는다.

```json
{ "domain": "쓰레기수거", "spatialScale": "한 동네",
  "environmentConditions": ["평일 교통량"],
  "objective": "민원이 가장 적은 수거 시각" }
```

| 순위 | 서버 | 점수 | 근거 / 어긋난 점 |
|---|---|---|---|
| 1 | `jangnyang-waste-sim` | 4 | 공간 규모: 한 동네 → 원룸촌 한 블록(block) · 환경 조건: 평일 교통량 → traffic-profile |
| 2 | `district-waste-sim` (가상) | 2 | 규모는 맞음 · **교통 지체 반영 미지원** — "이동시간을 구간 상수로만 다룬다" |
| — | `water-leak-sim` (가상) | — | 도메인 불일치로 **후보에서 제외**(점수로 깎지 않는다) |

근거가 **양쪽을 적는다** — 요청의 어느 항목이 카드의 어느 칸과 맞았는지. 한쪽만 적으면
근거가 아니라 결론이다. 2등에는 "왜 2등인지" 가 남는다.

---

## 5단계 — 질문 목록이 아니라 규칙집을 준다

[`05-get-templates.json`](05-get-templates.json) — 템플릿 14개.

```
truckCount               INTEGER  generateWhen=ALWAYS                       → numTrucks
dispatchIntervalMinutes  INTEGER  generateWhen=EFFECTIVE_TRUCKS_AT_LEAST_2  → dispatchIntervalMinutes
collectionTimeMinutes    INTEGER  generateWhen=ALWAYS                       → collectionTimeMinutes
```

`configField` 가 실행 설정으로 가는 **선언된 대응**이다. 이름 규약이 아니다.

---

## 6단계 — 서버가 되짚는다

[`06-plan-subtasks.json`](06-plan-subtasks.json) — 답을 둘만 주고 계산시켰다.

```
입력: {"numBuildings": 4, "truckCount": 1}

counts = {FILLED: 2, UNFILLED: 8, DEFERRED: 3, NOT_GENERATED: 1}   complete = false

NOT_GENERATED  jn.dispatchInterval     ← 1대라 배차 간격이 결과를 바꾸지 못한다
DEFERRED       jn.trafficProfile       ← trafficMode 를 아직 모른다
DEFERRED       jn.zoneAssignmentRule
DEFERRED       jn.intraZoneTravel
```

**`DEFERRED` 와 `NOT_GENERATED` 가 다르다.** "판단할 값이 아직 없다" 를 "필요 없다" 로 접으면
조건이 나중에 참이 되어도 그 결정을 영영 묻지 않는다.

이 자리는 **LLM 이 스스로 판정한 결과를 서버가 대조**하는 곳이기도 하다. 둘이 다르면 LLM 이
생성 조건을 잘못 읽었다는 뜻이고, 그 차이 자체가 측정 대상이다.

---

## 8단계 — 보정하지 않고 거절한다

[`08-validate-answers.json`](08-validate-answers.json)

```
valid = false
통과: {"seeds": 30, "routeAvailableCapacityKg": 150.5}

거절: truckType  OUT_OF_CLOSURE  허용되지 않은 값입니다: MEDIUM_3TON
                                 (허용: LARGE_5TON, MEDIUM_2P5T, SMALL_1TON)
거절: days       NOT_A_NUMBER    정수여야 합니다. 받은 값: 삼십일
```

가까운 값으로 고쳐 주지 않는다. 고치면 사용자가 요청한 것과 **다른 실험이 돌고** 그 사실이
아무 데도 남지 않는다. `"150.5"` 가 실수로 통과한 것도 그래서다 — 정수로 깎으면 배정 몫이
달라진다.

---

## 9·10단계 — 여기서 토큰이 나오지 않는다

[`09-build-scenario.json`](09-build-scenario.json)

서버 안에서 일어난 일: PES 평탄화 → 실행 설정 5벌 → `SimulationConfigValidator` 로 전부 검증
→ **되읽어 PES 와 대조**(역검증) → 통과.

```json
{ "scenarioId": "scn-4a7dc5dc", "valid": true, "runCount": 5,
  "blocks": [], "backVerificationBlocks": [],
  "unapprovedDefaults": { "routeAvailableCapacityKg": 150.0, "seeds": 5 },
  "state": "UNCONFIRMED" }
```

**검증 통과는 "돌릴 수 있다" 이고 확인은 "돌려도 된다" 다.** 둘을 한 단계로 합치면 "사용자가
확인하지 않은 것" 상태를 표현할 자리가 없어지고, 그러면 토큰이 확인을 뜻한다고 말할 수 없다.

---

## 12단계 — 여기만 LLM 을 거치지 않는다

**모델이 보는 것** — [`12a-status-before-confirm.json`](12a-status-before-confirm.json)

```json
{ "state": "UNCONFIRMED", "unapprovedDefaultCount": 2,
  "confirmUrl": "/confirm.html",
  "nextStep": "사용자에게 /confirm.html 을 열어 설정을 확인하도록 안내하십시오.
               확인은 사람만 할 수 있습니다." }
```

토큰 자리가 비어 있다. 모델에게는 **읽는 문만** 열려 있다 — 확인은 MCP 도구가 아니다.

**사람이 보는 것** — [`12a-confirm-screen-unconfirmed.html`](12a-confirm-screen-unconfirmed.html)

화면 맨 위에 **승인하지 않은 기본값 2개**(`routeAvailableCapacityKg` 150, `seeds` 5)를 경고로
띄운다. 그것을 못 보여주면 확인이 확인이 아니다. 그 아래에 값마다 출처(`USER` /
`MODEL_DEFAULT`)를 붙인 표와, 조건마다 달라지는 것(`collectionTimeMinutes` ·
`collectionTimeLabel`)을 낸다. **달라지는 필드는 서버가 알려주지 않고 화면이 설정들을
서로 비교해 찾는다.**

`이 설정으로 돌린다` 를 눌렀다 → [`12b-confirm-screen-confirmed.html`](12b-confirm-screen-confirmed.html)

```
CONFIRMED ·  확인 토큰 cft-d72a9866cc7b1f3d · 이 토큰으로만 실행됩니다.
             설정이 바뀌면 무효가 됩니다.
```

**모델이 다시 읽으면** — [`12c-status-after-confirm.json`](12c-status-after-confirm.json)

```json
{ "state": "CONFIRMED", "confirmToken": "cft-d72a9866cc7b1f3d",
  "nextStep": "run_scenario_by_token 에 confirmToken 을 실어 실행하십시오." }
```

쓰기는 사람, 읽기는 모델. 읽는 문으로는 동의를 만들 수 없고, 사람이 누를 때까지 기다렸다가
토큰을 집어 갈 수는 있다.

---

## 13·14단계 — 토큰으로만 실행

[`13-run-by-token.json`](13-run-by-token.json)

```
state = EXECUTED   notForOperationalUse = true
한계: 이 결과는 설정 간 비교이며 운영 예측이 아니다.

 수거시각    민원   최대적재    가동률   미수거  질량오차  시드
   360분      32     26.1kg    19.4%     0.0    0.00    42
   540분      64     31.7kg    19.8%     0.0    0.00    42
   720분      36     26.9kg    19.9%     0.0    0.00    42
   900분      33     26.1kg    20.1%     0.0    0.00    42
  1080분      30     26.1kg    20.1%     0.0    0.00    42
```

**결과가 평평하지 않다.** 09시(540분)에 봉우리가 생기는데, 배출 시각(07:22 / 08:58 / 14:00)과
수거 시각의 관계에서 생기는 내부 최적이다. 전부 같았다면 평탄화가 값을 싣지 못했거나 이
규모에서 그 축이 죽어 있다는 뜻이었다.

각 행에 함께 실려 나오는 것 — `seed` · `allTotals`(재현), `massBalanceErrorKg`(질량 보존
오차 0.00), `dataQualityFlags`, `assumptionNotes`, `coordinateQuality`. 능력 카드의
`alwaysAttachToResult` 규약이다: **"무엇으로 계산한 값인가" 를 결과만 보고 알 수 있어야 한다.**

---

## 거절 경로 둘 — 설계의 요점

**① 기존 도구를 토큰 없이 부르면 — 막지 않고 표시한다**
[`20-legacy-tool-without-token.json`](20-legacy-tool-without-token.json)

```
isError = false   confirmed = false
"서브태스크 흐름 밖에서 실행됐습니다 — 사용자가 이 설정을 확인하지 않았습니다."
```

`run_waste_simulation` 은 서브태스크 흐름 밖에서도 쓰인다. 막으면 기존 경로가 전부 멈추므로
막지 않고, 확인을 거쳤는지를 **결과가 스스로 밝힌다.**

**② 틀린 토큰이면 — 실행하지 않는다**
[`21-wrong-token-refused.json`](21-wrong-token-refused.json)

```
isError = true
{"code":"EXECUTION_ERROR","field":"confirmToken",
 "message":"확인 토큰이 이 시나리오의 현재 설정과 맞지 않습니다 —
            발급 이후 설정이 바뀌었거나 다른 시나리오의 토큰입니다."}
```

**없는 것과 틀린 것은 다르다.** 토큰을 줬다는 것은 확인을 주장한 것이고, 틀린 주장을
통과시키면 확인 절차가 아무것도 보장하지 못한다. 보관된 설정을 **실행 직전에 다시 해싱**하므로
발급 이후 누가 값을 바꿨어도 여기서 걸린다.

---

## 이 기록이 드러낸 것 하나

가동률이 **19~20%** 로 나온다. 범위 보고서
[`2026-09-23-장량동-실행가능-기능과-범위.md`](../2026-09-23-장량동-실행가능-기능과-범위.md) §9 는
배정용량 150kg 에서 60% 라고 적는데, 그 표는 **차량 1대 기준**이고 이번 실행은 3대다.
배정용량이 대수만큼 나뉘므로 1/3 이 된다.

§9 표에 대수가 명시돼 있지 않다. 그 표를 보고 150kg 을 고른 사람은 60% 를 기대하게 된다 —
**보고서에 "1대 기준" 을 덧붙여야 한다.**

---

## 재현 방법

```bash
./mvnw.cmd -B -DskipTests package
java -jar target/waste-sim-spring-1.1.1.jar
```

각 `NN-*.json` 의 `요청` 을 그대로 `POST /mcp` 에 보내면 된다. 한글이 든 본문은 **UTF-8 로
보내야 한다** — 셸이 CP949 로 내보내면 서버가 `BAD_REQUEST` 로 거절한다(이 기록을 만들며 실제로
겪었다). `curl --data-binary @파일` 이나 Python `urllib` 로 보내면 안전하다.

12단계만은 `http://localhost:8090/` 를 브라우저로 열어 직접 눌러야 한다. 그것이 이 설계의
핵심이다.
