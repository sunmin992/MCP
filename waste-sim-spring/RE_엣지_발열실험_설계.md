# R&E 실험 설계 — 라즈베리파이 스로틀링/회복 동역학과 방열판 배치 최적화

> 대상: 고등학생 연구팀
> 시스템: `waste-sim-spring` MCP 서버에 새로 붙은 엣지 발열 시뮬레이션 도구 3종
> 관련 코드: `src/main/java/com/wastesim/edge/`, 측정 스크립트: `scripts/edge/`

---

## 1. 이 도구가 하는 일과 하지 않는 일

이번에 추가된 것은 **실측을 대체하는 시뮬레이터가 아니라, 실측을 늘려 주는 시뮬레이터**다.
라즈베리파이로 직접 잴 수 있는 조건은 몇 개 되지 않는다(보드 2종 × 냉각 3종만 해도
한 셀에 30분씩 걸린다). 실제로 잰 몇 개 조건으로 모델을 보정한 뒤, 나머지 조건을
계산으로 채우는 것이 목표다.

| 연구 질문(계획서) | 담당 도구 | 나오는 값 |
|---|---|---|
| Q1. 보드·냉각 조건에 따라 TTT가 어떻게 달라지는가 | `simulate_edge_throttling` | TTT, 소프트 제한 진입 시각, 온도/클럭/FPS 시계열 |
| Q2. 제어·냉각 정책에 따라 TRT가 어떻게 달라지는가 | `simulate_edge_throttling` (recoveryPolicy) | TRT_state / TRT_service / TRT_full, TED |
| Q3. 초기 시계열만으로 TTT·TRT를 예측할 수 있는가 | `calibrate_edge_thermal_model` | τ_h, τ_c, R_ja, C_th + 적합 품질(R², RMSE) |
| Q4(확장). 방열판을 어떻게 배치해야 효율이 좋은가 | `simulate_heatsink_layout` | 후보별 R_ja·정상상태 온도·TTT 순위, 열저항 분해, 개선 힌트 |

**하지 못하는 것**: SoC 내부의 공간적 온도 분포(hot spot의 위치)는 계산하지 않는다.
그건 열화상 카메라(MLX90640 / Lepton)로 관측할 몫이고, 시뮬레이터는 그 관측을
`hotspots` 입력으로 받아 "방열판이 그 지점을 덮는가"만 판정한다.

---

## 2. 5분 만에 첫 결과 보기

### 2.1 Windows PowerShell (권장)

PowerShell에서는 `curl`이 `Invoke-WebRequest`의 **별칭**이라 `-d`·`-H` 옵션을 모르고,
`jq`도 없고, 줄바꿈 `\`도 동작하지 않는다. 아래 헬퍼를 한 번 불러 두면 그 세 가지를
전부 우회한다(한글 응답이 깨지는 PowerShell 5.1 인코딩 문제도 함께 처리한다).

```powershell
# 1) 서버 실행 (별도 창)
mvn spring-boot:run

# 2) 헬퍼 로드 — 맨 앞의 점(.)을 빠뜨리지 말 것(현재 세션에 함수를 심는다)
. .\scripts\edge\Invoke-Mcp.ps1

# 3) 도구 목록 — 새 도구 3개가 보여야 한다
Get-McpTools

# 4) 첫 시뮬레이션 — Pi5, 무냉각, 여름철(35℃), 최대 부하 15분
$r = Invoke-Mcp simulate_edge_throttling @{
    board='pi5'; cooling='bare'; ambientTempC=35
    workloadMode='max_throughput'; loadSeconds=900
    recoveryPolicy='r1_stop'; recoverySeconds=600; sampleIntervalSeconds=15
}
$r.metrics
$r.notes

# 5) 실측 CSV 캘리브레이션 (Python 없이 PowerShell만으로)
Import-McpCalibration -CsvPath .\runs\<run_id>.csv -Board pi5 -AmbientC 26.5 -LoadEndSec 1320
```

헬퍼 없이 한 줄로 확인만 하려면:

```powershell
$body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
(Invoke-RestMethod http://localhost:8090/mcp -Method Post -ContentType 'application/json' -Body $body).result.tools.name
```

원래의 curl 문법을 그대로 쓰고 싶다면 `curl`이 아니라 **`curl.exe`**(Windows 10 이상 기본
포함)를 쓰고, 줄바꿈은 `\`가 아니라 백틱(`` ` ``)을 쓴다.

### 2.2 macOS / Linux / Git Bash

```bash
# 도구 목록
curl -s localhost:8090/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'

# 첫 시뮬레이션 — 도구 결과는 content[0].text 안에 JSON 문자열로 한 번 더 감싸여 온다
curl -s localhost:8090/mcp -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"simulate_edge_throttling","arguments":{
    "board":"pi5","cooling":"bare","ambientTempC":35,
    "workloadMode":"max_throughput","loadSeconds":900,
    "recoveryPolicy":"r1_stop","recoverySeconds":600,
    "sampleIntervalSeconds":15}}}' | jq -r '.result.content[0].text' | jq '.metrics, .notes'
```

결과의 `notes` 배열을 항상 먼저 읽을 것. "왜 TTT가 null인지", "왜 회복이 안 됐는지"가
문장으로 들어 있어서, 값만 보고 실험이 실패했다고 오해하는 일을 막아 준다.

### 2.3 채팅창에서 한국어로 물어보기 (학생용 기본 경로)

JSON을 만들 필요 없이 채팅창에 그냥 물어봐도 된다. 서버가 요청을 읽고 장량동 모델과
라즈베리파이 모델 중 어느 쪽인지 정한 뒤 같은 MCP 도구를 부른다 — **채팅으로 물었을 때와
MCP로 불렀을 때 결과는 항상 같다**(같은 도구 구현체를 호출한다).

```
"라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?"
"pi4에 방열판 달고 15fps로 실행, 실내 28도"
"스로틀링 걸린 다음 팬 100%로 켜면 얼마나 빨리 회복돼?"
"pi5에 방열판 어떻게 붙여야 제일 시원해?"
"cal-001 프로파일로 주변 35도일 때 시뮬레이션 해줘"
```

알아 두면 좋은 세 가지.

- **보드는 반드시 말해야 한다.** "발열 시뮬레이션 돌려줘"처럼 보드가 없으면 서버가 값을
  지어내지 않고 되묻는다. Pi4와 Pi5는 발열 특성이 많이 다르다.
- **방열판 배치는 표준 후보 6종으로 비교한다.** 치수를 말로 다 받으면 LLM이 숫자를 지어낼
  위험이 있어, 채팅에서는 A(기준)를 축으로 한 번에 한 요인만 바꾼 고정 후보를 쓴다
  (§6.2 가설 H1~H5와 1:1). 직접 잰 치수로 비교하려면 MCP로 도구를 직접 호출한다.
- **캘리브레이션은 채팅으로 못 한다.** 측정 시계열을 메시지에 실을 수 없어서, 요청하면
  보내는 방법(§5 절차 8번)과 저장된 프로파일 목록을 안내한다.

보드·냉각조건·운용모드·회복정책 같은 "어떤 실험을 돌렸는지가 바뀌는 값"은 LLM이 아니라
정규식이 이번 메시지에서 직접 읽는다. 덕분에 같은 문장은 언제 보내도 같은 조건으로 실행되고
(실험 재현성), LLM 백엔드가 죽어 있어도 엣지 요청은 그대로 동작한다.

---

## 3. 실험 요인과 실행 매트릭스

### 3.1 요인·수준

| 요인 | 수준 | 비고 |
|---|---|---|
| 보드 | Pi4, Pi5 | 2 |
| 냉각 | Bare, Passive(방열판), Active(팬) | 3 |
| 운용 모드 | 동일 목표 FPS, 최대 처리량 | 2 — 목표 FPS가 주 비교 대상 |
| 회복 정책 | R1 완전중지, R2 저부하(25%), R3 능동냉각, 무조치(대조군) | 4 |
| 주변 온도 | 실측 실내온도 1수준 + (가능하면) 5℃ 높인 조건 | 통제하되 반드시 기록 |

TTT 실험(Q1)은 `보드 2 × 냉각 3 × 모드 2 = 12셀`, TRT 실험(Q2)은
`보드 2 × 냉각 3 × 정책 4 = 24셀`이다. 전부 실측하면 한 셀 30분 × 36셀 = 18시간이 넘는다.
**실측은 아래 6셀만 하고 나머지는 시뮬레이션으로 채우는 것을 권한다.**

| 우선순위 | 실측할 셀 | 이유 |
|---|---|---|
| 1 | Pi5 × Bare × 최대 처리량 | 스로틀링이 확실히 발생 → TTT·TED 원자료 확보 |
| 2 | Pi5 × Passive × 최대 처리량 | 방열판 효과의 기준점(R_ja 캘리브레이션) |
| 3 | Pi5 × Active × 최대 처리량 | 냉각 상한 |
| 4 | Pi4 × Bare × 최대 처리량 | 보드 세대 비교 |
| 5 | Pi4 × Passive × 최대 처리량 | 〃 |
| 6 | Pi5 × Bare × R3 회복 | 회복 정책 실측 1점(나머지 정책은 외삽) |

각 셀 **3회 반복**한다. 1회로는 "우연히 그랬는지"를 구분할 수 없고, 3회면 평균과 범위를
같이 보고할 수 있다.

### 3.2 반드시 통제해야 할 것

- **잔열**: 직전 실행의 열이 남아 있으면 TTT가 짧게 나온다. 매 실행 전 유휴 상태로
  최소 10분(또는 SoC 온도가 유휴 정상상태 ±2℃ 안에 들어올 때까지) 식힌다.
  `measure_throttling.py`의 BASELINE 단계가 이 확인용이다.
- **실내 온도**: 창문·에어컨·사람 수에 따라 쉽게 3℃ 움직인다. 매 실행마다 온도계로
  재서 `--ambient` 로 넣는다. **이 값이 없으면 R_ja를 계산할 수 없다.**
- **전원**: 정품 어댑터·정품 케이블. 저전압(get_throttled 0x1)이 뜨면 그 실행은 버린다.
- **추론 모델·입력**: 같은 모델 파일, 같은 정밀도(FP32/INT8), 같은 입력 해상도.
  모델이 바뀌면 소비전력이 바뀌고 그럼 다른 실험이 된다.
- **보드 자세와 주변**: 눕힌 채/세운 채, 케이스 유무, 벽과의 거리를 고정하고 사진으로 남긴다.

### 3.3 실행 순서

같은 조건을 연달아 3번 하지 말고, 조건을 섞어 돌린다(하루의 실내 온도 변화가 특정
조건에만 몰리는 것을 막는다). 예: A1 B1 C1 A2 B2 C2 A3 B3 C3.

---

## 4. 로그 스키마 — 지금 확정해야 나중에 되돌릴 수 있다

실험이 끝난 뒤에 "이 CSV가 어떤 조건이었지?"를 복원할 방법은 없다. 아래 형식을
첫 실행부터 지킨다(`measure_throttling.py`가 이대로 남긴다).

### 4.1 시계열 CSV (`<run_id>.csv`)

| 열 이름 | 단위 | 필수 | 설명 |
|---|---|---|---|
| `t_sec` | 초 | ✅ | 실행 시작 후 경과 시간 |
| `iso_time` | ISO 8601(UTC) | ✅ | 절대 시각. 열화상·전력계 로그와 맞출 때 쓴다 |
| `phase` | BASELINE/LOAD/RECOVERY | ✅ | 단계 구분 |
| `soc_temp_c` | ℃ | ✅ | SoC 온도 |
| `clock_mhz` | MHz | ✅ | ARM 클럭 |
| `throttled` | 0/1 | ✅ | get_throttled의 0x4 비트 |
| `throttled_bits` | 16진수 | ✅ | 원본 비트 전체(0x1 저전압 판별에 필요) |
| `power_w` | W | 권장 | 전력계 없으면 비움 |
| `volts` | V | 권장 | core 전압 |
| `fan_rpm` | rpm | 조건부 | Active 조건에서 |
| `fps` | frame/s | ✅ | 실제 달성 처리량 |

### 4.2 메타데이터 JSON (`<run_id>.json`)

`board, cooling, mode, target_fps, load_seconds, recovery_policy, recovery_seconds,
ambient_temp_c, model, precision, started_at_iso, load_kind, notes`

### 4.3 파일 이름 규칙

```
<board>-<cooling>-<mode>-<MMDD-HHMMSS>.csv      예: pi5-passive-target_fps-0803-142530.csv
```

### 4.4 시각 동기화

모든 장비(보드, 열화상 카메라, 전력 로거)의 시계를 NTP로 맞추고, 실험 시작 시
**한 번 손뼉을 치듯** 눈에 띄는 이벤트(예: 팬을 껐다 켜기)를 만들어 각 로그에
같은 순간이 찍히게 한다. 나중에 로그를 겹칠 때 이 표시가 없으면 초 단위 정렬이 안 된다.

---

## 5. 측정 절차 체크리스트

1. [ ] 실내 온도계 값 기록 → `--ambient`
2. [ ] 보드가 유휴 온도로 식었는지 확인(직전 실행 잔열 제거)
3. [ ] 전원·케이블 확인, `vcgencmd get_throttled` 가 `0x0`인지 확인
4. [ ] 열화상 카메라 위치·초점 고정(같은 각도·거리, 자를 함께 촬영해 좌표 기준 만들기)
5. [ ] `measure_throttling.py` 실행 — 예:
   ```bash
   python3 scripts/edge/measure_throttling.py \
       --board pi5 --cooling passive --mode max_throughput \
       --baseline 120 --load 1200 --recovery r3_active_cooling --recovery-seconds 900 \
       --ambient 26.5 --model mobilenet-ssd --precision int8 --label "pi5-passive-int8"
   ```
6. [ ] 실행 중 30초마다 출력되는 온도가 예상 범위인지 눈으로 확인(90℃ 도달 시 자동 중단)
7. [ ] 종료 후 CSV·JSON이 생겼는지, 열이 비어 있지 않은지 확인
8. [ ] 캘리브레이션:
   ```bash
   python3 scripts/edge/csv_to_mcp_payload.py runs/<run_id>.csv --post
   ```
   → 출력된 `profileId`(예: `cal-001`)를 실험 노트에 적는다

---

## 6. 도구별 사용법

### 6.1 `simulate_edge_throttling` — TTT·TED·TRT 예측

| 인자 | 기본값 | 설명 |
|---|---|---|
| `board` | (필수) | `pi4` / `pi5` |
| `cooling` | `passive` | `bare` / `passive` / `active` |
| `ambientTempC` | 25 | 주변 온도 |
| `workloadMode` | `target_fps` | `target_fps` / `max_throughput` |
| `targetFps` | 10 | 목표 FPS |
| `maxFps` | 보드 기본값 | **실측한 무스로틀 FPS로 덮어쓸 것** |
| `loadSeconds` | 900 | 고부하 유지 시간 |
| `recoveryPolicy` | `none` | `r1_stop` / `r2_low_load` / `r3_active_cooling` / `none` |
| `recoverySeconds` | 600 | 회복 관찰 시간 |
| `applyRecoveryOnThrottle` | true | 스로틀링이 감지된 순간 정책 적용(정책 간 공정 비교용) |
| `profileId` | — | 캘리브레이션 결과 id. 넣으면 실측 파라미터로 계산 |

결과의 `metrics`가 곧 결과지 한 줄이다.

```
softLimitEntrySec  80℃(소프트 제한) 진입 — 성능 저하가 시작되는 시점
tttSec             85℃(0x4) 지속 감지 — 계획서의 TTT
episodeCount/medianTedSec   스로틀링 에피소드 개수와 지속시간 중앙값 = TED
trtStateSec        비트 해제까지(회복 판정은 30초 연속 해제 기준)
trtServiceSec      달성 가능 FPS가 기준의 90%로 복원될 때까지
trtFullSec         유휴 온도 +2℃까지 완전 냉각
fpsDropPercent     스로틀링으로 인한 최대 FPS 하락률
```

> **`tttSec`이 null로 나오는 게 정상인 경우가 있다.** 소프트 제한(80℃)에서 클럭을
> 낮추는 것만으로 열이 잡히면 85℃까지 가지 않는다 — 실제 Pi4가 무냉각·상온에서
> 80℃ 부근에 "눌러앉는" 현상이 이것이다. 이때는 `softLimitEntrySec`이 성능 저하
> 시작점이고, 하드 스로틀링을 관측하려면 주변 온도를 올리거나(케이스 씌우기) 부하를
> 더 키워야 한다. 이 사실 자체가 실험 조건 설계의 근거가 되므로 보고서에 쓸 것.

### 6.2 `simulate_heatsink_layout` — 방열판 배치 최적화

**좌표계**: SoC 패키지 중심이 원점, 단위 mm. `baseLengthMm`이 X축(핀이 뻗은 방향),
`baseWidthMm`이 Y축(핀이 나열된 방향). 열화상 사진에 자를 같이 찍어 좌표를 읽고,
학생끼리 축 방향을 한 번 합의해 고정한다.

```json
{"board":"pi5","ambientTempC":28,"workloadMode":"max_throughput","layouts":[
  {"name":"A 중앙정렬 큰핀",
   "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
               "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
   "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"aligned"},
   "airflow":{"type":"natural"},
   "tim":{"type":"pad","thicknessMm":0.5}},

  {"name":"B 15mm 어긋남", "…placement":{"offsetXMm":15}},

  {"name":"C 소형+팬",
   "heatsink":{"baseLengthMm":20,"baseWidthMm":20,"finCount":6,"finHeightMm":8,"finThicknessMm":1},
   "airflow":{"type":"forced","fanRpm":4000,"fanDistanceMm":8},
   "tim":{"type":"paste","thicknessMm":0.05},
   "hotspots":[{"name":"PMIC","xMm":22,"yMm":-6,"powerW":1.2}]}
]}
```

결과는 정상상태 온도 오름차순 랭킹이며, 무냉각 기준선이 자동으로 끼어든다. 각 후보마다:

- `resistanceBreakdown` — 열저항을 항별로 분해(`rTim`, `rMisalign`, `rSpread`, `rConv` …)
- `dominantResistance` / `improvementHint` — **가장 큰 항**과 그에 대한 고정 개선 규칙
- `warnings` — 덮임률 부족, 핀 간격 과밀, 팬 거리 과다, 미커버 hotspot 등

**배치 실험으로 확인할 만한 가설**(전부 이 도구로 먼저 예측하고 실측으로 검증):

| 가설 | 바꿀 인자 |
|---|---|
| H1. 오프셋이 커질수록 온도가 오른다(덮임률 손실) | `placement.offsetXMm` 0/5/10/15/20 |
| H2. 같은 방열판도 핀 방향에 따라 다르다 | `placement.finAlignment` aligned/cross |
| H3. 팬은 가까이 붙이는 것이 RPM을 올리는 것보다 싸다 | `airflow.fanDistanceMm` 5/30/80 |
| H4. 얇은 그리스 > 두꺼운 패드 | `tim.type`·`thicknessMm` |
| H5. 자연대류에서 핀을 너무 촘촘히 하면 오히려 나빠진다 | `finCount` 8/16/40 |

### 6.3 `calibrate_edge_thermal_model` — 실측 → 모델

실측 시계열을 넣으면 `T(t) = T∞ − (T∞−T0)·e^(−t/τ)` 를 맞춰 τ, R_ja, C_th를 역추정한다.
`throttled` 열이 있으면 **실측 TTT/TED/TRT**도 함께 뽑아 준다.

- 입력은 `samples` 배열 또는 `samplesCsv` 문자열(측정 스크립트 출력 그대로).
- `fitQuality`가 "불량"이면 그 파라미터를 쓰면 안 된다. 흔한 원인: 유휴 구간이 섞임
  (변환 스크립트가 자동으로 잘라낸다), 실내 온도가 실험 중 변함, 부하가 불안정.
- 반환된 `profileId`를 시뮬레이션 도구에 넣으면 그때부터 **문헌 추정치가 아니라
  학생이 잰 보드**로 계산한다. `ambientTempC`를 함께 주면 "같은 보드를 더운 방에서
  돌리면?"처럼 실측하지 않은 조건으로 외삽할 수 있다.

---

## 7. 시뮬레이션 ↔ 실측 비교표 (보고서 핵심 표)

캘리브레이션에 쓴 셀 말고 **다른 셀**로 검증해야 의미가 있다(같은 데이터로 맞추고
같은 데이터로 검증하면 당연히 맞는다).

| 조건 | 지표 | 실측 | 시뮬(프리셋) | 시뮬(캘리브레이션 후) | 오차 |
|---|---|---|---|---|---|
| Pi5·Passive·최대 | TTT(s) | | | | |
| 〃 | 정상상태 온도(℃) | | | | |
| 〃 | FPS 하락률(%) | | | | |
| Pi5·Bare·R3 | TRT_state(s) | | | | |

오차는 상대오차 `|시뮬−실측| / 실측 × 100(%)` 로 쓰고, 3회 반복의 평균과 범위를 함께 적는다.
**"프리셋"과 "캘리브레이션 후"를 나란히 두는 것**이 이 연구의 기여를 보여주는 지점이다 —
보정이 오차를 얼마나 줄였는지가 수치로 나온다.

---

## 8. 모델의 가정과 한계 (보고서에 반드시 명시)

1. **단일 노드 lumped 모델**이다. SoC를 온도 한 점으로 본다. TTT/TED/TRT는 모두
   온도 센서 한 점 기준이라 이 가정으로 충분하지만, 칩 내부 온도 분포는 말할 수 없다.
2. **대류 열전달계수 h와 오정렬 상수 MISALIGN_K는 경험식**이다. 절대 온도 예측은
   ±30% 오차를 가질 수 있다. 신뢰할 수 있는 것은 **후보 간 상대 비교(Δ℃)**다.
3. **펌웨어 거버너는 근사**다. 실제 라즈베리파이 펌웨어의 클럭 제어 알고리즘은 공개돼
   있지 않다. 소프트 제한(80℃, 클럭 하향)과 하드 스로틀링(85℃, 클럭 하한)의 2단
   히스테리시스로 모형화했다.
4. **보드 기본값은 문헌 추정치**다(`BoardType` 참고). 반드시 캘리브레이션해서 쓸 것.
5. 방열판 모델은 **정상 상태 열저항**만 계산한다. 방열판을 붙이면 열용량 C_th도 커지는데
   (시정수가 길어진다) 그 증가분은 계산하지 않는다 — 방열판을 단 상태로 캘리브레이션하면
   C_th가 실측으로 잡히므로, 배치 비교 시 `profileId`를 함께 넣는 것을 권한다.

---

## 9. 안전

- 이 실험은 보드를 의도적으로 85℃ 이상으로 만든다. **사람이 자리를 지킬 것.**
- 가연물이 없는 평평한 곳, 종이·천 위 금지.
- 측정 스크립트는 90℃에서 자동 중단하지만, 이상한 냄새·변색이 보이면 즉시 전원 차단.
- 팬을 GPIO로 제어할 때 배선을 잘못하면 보드가 손상된다. 팬 제어는
  `measure_throttling.py`의 `set_fan()` 한 곳에만 있으니, 배선에 맞게 그 함수만 고칠 것.
- 저전압(0x1) 상태로 실험하면 데이터가 무의미할 뿐 아니라 SD카드가 손상될 수 있다.

---

## 10. 자주 하는 실수

| 증상 | 원인 | 해결 |
|---|---|---|
| `tttSec`이 계속 null | 소프트 제한에서 열이 잡힘(정상) | `notes` 확인. 주변 온도↑ 또는 부하↑ |
| 캘리브레이션 `fitQuality` 불량 | 유휴 구간이 섞임 / 실내온도 변동 | 변환 스크립트로 BASELINE 자동 절단, 실내온도 재확인 |
| R_ja가 이상하게 큼 | `ambientTempC`를 잘못 넣음 | 실제 실내 온도로 다시 계산 |
| TRT가 전부 0에 가까움 | 스로틀링 비트는 원래 몇 초 만에 풀린다 | TRT_service·TRT_full로 정책을 비교할 것 |
| 실측 FPS가 목표보다 낮음 | 목표 FPS가 보드 능력 초과 | `maxFps`를 실측값으로 넣고 다시 설계 |
| 실행마다 TTT가 들쭉날쭉 | 직전 실행 잔열 | 유휴 복귀 확인 후 시작(BASELINE 단계) |
