# Waste Sim Spring 디버깅 점검 목록

작성일: 2026-08-03  
대상: `C:\Dev\MCP\waste-sim-spring`

## 1. 점검 개요

이 문서는 정적 코드 검토에서 확인한 폐기물 시뮬레이션, REST/MCP 입력 처리, 엣지 열 모델의 결함 후보와 보완 사항을 정리한다.

- 이번 점검에서는 코드를 수정하지 않았다.
- 작업 트리에 이미 존재하던 `EdgeToolSupport.java`, `FanModelTest.java` 변경도 보존했다.
- 시스템 PATH에 Maven 실행 파일이 없어 이번 점검 중 `mvn test`를 새로 실행하지 못했다.
- `target/surefire-reports`에는 2026-08-03 생성된 테스트 성공 기록이 있다.
- 기존 테스트 성공은 아래 결함과 모순되지 않는다. 대부분 현재 테스트가 다루지 않는 경계조건이다.

우선순위 기준:

- **P1**: 정상적인 외부 입력이나 일반 기능 사용에서 결과를 직접 왜곡할 수 있음
- **P2**: 특정 경계 입력, 불완전한 측정 데이터, 큰 설정에서 잘못된 결과 또는 혼란을 유발함
- **P3**: 즉시 장애를 만들지는 않지만 운영성·유지보수성·신뢰도를 낮춤

---

## 2. 폐기물 시뮬레이션 및 공통 입력 검증

### W-01. 사용자 지정 `wasteTypes` 내부 값이 검증되지 않음 — P1

관련 파일:

- `src/main/java/com/wastesim/tool/SimulationConfigValidator.java`
- `src/main/java/com/wastesim/model/WasteType.java`
- `src/main/java/com/wastesim/simulation/SimulationEngine.java`

현재 검증기는 최상위 `capacity`, `threshold`만 검사하고 `wasteTypes` 배열 내부 값은 검사하지 않는다.

문제가 되는 입력 예:

```json
{
  "wasteTypes": [
    {
      "key": "GENERAL",
      "fraction": 1.5,
      "capacity": 0,
      "threshold": -1,
      "intervalDays": 1
    }
  ]
}
```

영향:

- `capacity <= 0`: 엔진이 적재 비율을 `0.0`으로 처리하여 적재량이 늘어도 민원이 발생하지 않을 수 있다.
- `threshold < 0`: 사실상 모든 양수 배출이 민원으로 처리된다.
- `fraction < 0`: 해당 폐기물 배출이 조용히 무시된다.
- `fraction` 합계가 1 초과: 실제 배출량보다 많은 폐기물이 생성된다.
- 중복되거나 비어 있는 `key`: 결과 식별과 비교가 불명확해진다.

권장 수정:

- 각 `WasteType`에 대해 다음을 검증한다.
  - `key`가 null/blank가 아닌지
  - `fraction`이 `0..1`인지
  - `capacity > 0`인지
  - `threshold`가 `0..1`인지
  - `intervalDays >= 1`인지
- `fraction` 합계가 허용 오차 내에서 `1.0`인지 검사한다.
- `key` 중복을 거부한다.

권장 테스트:

- 용량 0, 음수 임계값, 음수 비율 거부
- 비율 합계 0.9 또는 1.1 거부
- 정상적인 분리배출 비율 합계 1.0 통과

### W-02. 복수·주말 수거 시각 범위 검증 누락 — P1

관련 파일:

- `src/main/java/com/wastesim/tool/SimulationConfigValidator.java`
- `src/main/java/com/wastesim/model/SimulationConfig.java`
- `src/main/java/com/wastesim/simulation/SimulationEngine.java`

현재 `collectionTimeMinutes` 하나만 `0..1439` 범위인지 검사한다. 다음 필드는 검증되지 않는다.

- `collectionTimesMinutes`
- `weekendCollectionTimeMinutes`

재현 입력 예:

```json
{
  "collectionTimesMinutes": [-30, 1500],
  "weekendCollectionTimeMinutes": 2000
}
```

영향:

- 음수 시간은 시뮬레이션 시작 전 수거 이벤트를 만든다.
- 1440 이상은 의도와 다르게 다음 날짜 시간으로 넘어간다.
- 결과의 일별 집계와 교통 프로필 적용 시각이 어긋난다.

권장 수정:

- 모든 수거 시각 필드에 동일한 `0..1439` 검증을 적용한다.
- 복수 시각의 중복 여부를 확인하고 중복을 거부하거나 명시적으로 제거한다.
- 시간 배열이 지나치게 큰 경우 상한을 둔다.

권장 테스트:

- `[-1]`, `[1440]`, 주말 시각 `-1`/`1440` 거부
- `[540, 1080]` 통과
- 중복 시각 `[540, 540]` 처리 정책 고정

### W-03. 수거 주기가 긴 날에도 차량 이동과 교통 민원이 계산됨 — P1

관련 파일:

- `src/main/java/com/wastesim/simulation/SimulationEngine.java`

`isTruckDay()`는 공휴일과 주말만 검사한다. `collectionIntervalDays`는 차량 이벤트 생성 조건에 반영되지 않고, 실제 용기를 비울 때만 `WasteType.intervalDays`로 검사한다.

영향:

- 격일 수거에서도 차량 경로 이벤트가 매일 생성된다.
- 실제로 아무 용기도 비우지 않는 날에도 RED 구간 교통 민원이 누적된다.
- 평균 수거 완료 시간도 수거하지 않은 날의 경로를 포함한다.
- 다중 폐기물 유형에서 전부 비수거일인 날에도 차량이 움직인다.

권장 수정:

- 날짜별로 하나 이상의 폐기물 유형이 실제 수거 대상인지 먼저 계산한다.
- 수거 대상이 하나도 없으면 해당 날짜의 차량·경로 이벤트를 생성하지 않는다.
- 전역 `collectionIntervalDays`와 개별 `WasteType.intervalDays`의 우선순위 또는 결합 규칙을 문서화한다.

권장 테스트:

- 격일 수거 4일 실행 시 차량 경로가 2일만 생성되는지 확인
- 비수거일에는 교통 민원이 증가하지 않는지 확인
- 분리배출 유형별 수거 주기가 서로 다른 경우 필요한 날만 차량이 운행되는지 확인

### W-04. `HH:MM` 파서가 잘못된 분 값을 정상 시각으로 바꿈 — P2

관련 파일:

- `src/main/java/com/wastesim/model/SimulationConfig.java`
- `src/main/java/com/wastesim/tool/ConfigArgs.java`

`hhmmToMinutes()`는 시와 분의 개별 범위를 검사하지 않는다.

재현 예:

```text
12:99 -> 819분 -> 13:39로 출력
24:00 -> 1440분 -> 이후 검증에서 거부
8:30  -> 510분 -> 08:30으로 정상화
```

`12:99`는 총 분이 하루 범위 안이므로 검증을 통과할 수 있다는 점이 핵심 문제다.

권장 수정:

- 정규식 또는 엄격한 파서로 `H:MM`/`HH:MM` 형식을 검사한다.
- 시 `0..23`, 분 `0..59`를 개별 검증한다.
- 잘못된 형식은 `IllegalArgumentException` 대신 구조화된 검증 오류로 반환한다.

권장 테스트:

- `8:30`, `08:30`, `23:59` 통과
- `12:99`, `24:00`, `12:`, `:30`, `abc` 거부

### W-05. 건물 수가 26을 넘으면 잘못된 노드 ID가 생성됨 — P2

관련 파일:

- `src/main/java/com/wastesim/simulation/SimulationEngine.java`
- `src/main/java/com/wastesim/tool/SimulationConfigValidator.java`

`nodeId()`는 `'A' + buildingIndex` 방식이어서 27번째 건물부터 `Node_[`, `Node_\\` 같은 ID가 생성된다. 반면 `nodeIndex()`는 영문 알파벳 한 글자만 허용한다.

영향:

- 27개 이상 건물에서 생성과 역변환 규칙이 불일치한다.
- 경로 검증, 교통 노드 대응, UI 표시가 깨진다.

권장 수정:

- 현재 설계를 유지한다면 `numBuildings <= 26`을 검증한다.
- 26개 이상을 지원해야 한다면 `Node_AA` 방식 등 가역적인 ID 체계로 교체한다.

### W-06. 확장 설정 필드의 검증 범위가 부족함 — P2

검토가 필요한 필드:

- `collectionIntervalDays`
- `landlordThreshold`
- `landlordInspectMinutes`
- `returnFraction`
- `monthlyWasteFactor`
- `trafficComplaintWeight`
- `dispatchIntervalMinutes`
- `routeTravelMinutes`
- `holidays`

일부 setter가 값을 강제로 보정하지만, 잘못된 요청을 조용히 다른 값으로 바꾸는 것보다 검증 오류로 돌려주는 편이 API 신뢰성에 유리하다.

권장 검증 예:

- 비율·임계값: `0..1`
- 하루 중 시각: `0..1439`
- 기간·간격: 음수 금지 및 현실적인 상한
- 월별 가중치: 유한한 양수, 배열 길이 정책 고정
- 공휴일: 시뮬레이션 기간 안의 중복 없는 날짜 인덱스

---

## 3. REST 및 MCP 경계조건

### A-01. MCP 도구의 required 스키마가 서버 실행 시 강제되지 않음 — P2

관련 파일:

- `src/main/java/com/wastesim/mcp/McpController.java`
- `src/main/java/com/wastesim/mcp/McpToolCatalog.java`
- `src/main/java/com/wastesim/tool/ConfigArgs.java`

MCP `inputSchema`에는 `collectionTime` 등의 required 필드가 있지만, `tools/call`에서는 JSON Schema 검증 없이 기본 `SimulationConfig`를 생성한다.

영향:

- 필수 필드가 빠져도 기본값 12:00으로 실행될 수 있다.
- 클라이언트는 자신의 요청이 불완전했음을 알지 못한다.

권장 수정:

- 도구별 required 필드를 서버에서도 검증한다.
- 가능하면 공개한 JSON Schema와 같은 검증 규칙을 실행 시점에 재사용한다.

### A-02. 비교 API의 입력 오류 정책을 명확히 할 필요가 있음 — P3

관련 파일:

- `src/main/java/com/wastesim/controller/SimulationController.java`
- `src/main/java/com/wastesim/web/CompareRequest.java`

현재 null 또는 빈 `times`는 setter가 무시하여 기본 비교 시각으로 대체된다. 사용자가 빈 배열을 실수로 보낸 경우에도 성공 응답이 나와 요청 오류를 발견하기 어렵다.

권장 수정:

- 빈 배열을 기본값으로 처리할지 400으로 거부할지 API 계약에 명시한다.
- 비교 가능한 시각 개수에 상한을 두어 과도한 다중 seed 실행을 방지한다.

---

## 4. 엣지 열·스로틀링 모델

### E-01. R2 저부하 정책이 `MAX_THROUGHPUT`에서 작동하지 않음 — P1

관련 파일:

- `src/main/java/com/wastesim/edge/ThermalSimulator.java`

현재 R2 정책은 다음과 같이 `effTarget`만 25%로 줄인다.

```java
double effTarget = recovering && policy == R2_LOW_LOAD
        ? targetFps * LOW_LOAD_FACTOR
        : targetFps;
```

그러나 `effTarget`은 `TARGET_FPS` 분기에서만 사용된다. `MAX_THROUGHPUT` 분기는 기존 `level`을 그대로 사용한다.

영향:

- `MAX_THROUGHPUT + R2_LOW_LOAD`에서 부하, FPS, 전력, 온도가 줄지 않는다.
- R2가 사실상 무조치 정책처럼 동작한다.
- R1/R2/R3 회복 정책 비교 결과가 잘못된다.

권장 수정:

- 회복 중 R2이면 최대 처리량 모드에서도 유효 부하를 25%로 줄인다.

개념 예:

```java
double effectiveLevel = recovering && policy == R2_LOW_LOAD
        ? level * LOW_LOAD_FACTOR
        : level;
```

권장 테스트:

- `MAX_THROUGHPUT`에서 R2 전력이 무조치보다 낮은지
- R2 온도 및 TRT가 무조치보다 개선되는지
- R1은 FPS 0, R2는 25% 수준, R3는 부하 유지라는 정책 차이가 보존되는지

### E-02. 종료 시각 이후 한 스텝을 추가 적분함 — P1

관련 파일:

- `src/main/java/com/wastesim/edge/ThermalSimulator.java`

루프 조건이 다음과 같다.

```java
while (t <= endTime + 1e-9) {
    // 현재 상태 집계
    energyJ += powerW * dt;
    // 온도 적분
    t += dt;
}
```

`t == endTime`인 반복에서도 `dt` 전체를 적분한다.

영향:

- 실행 시간이 `dt`만큼 길어진다.
- SoC 에너지와 팬 에너지가 과대 계산된다.
- 마지막 적분에서 상승한 온도가 peak에 반영될 수 있다.
- `dt`가 큰 직접 호출일수록 오차가 커진다.

권장 수정:

- 상태 샘플과 구간 적분을 분리한다.
- 마지막 적분 폭을 `step = min(dt, endTime - t)`로 계산한다.
- `t == endTime`에서는 최종 샘플만 기록하고 에너지는 추가하지 않는다.

권장 테스트:

- 동적 전력 0, 상수 소비전력 조건에서 `energyJ == powerW * durationSec`
- 팬 에너지 역시 `fanPowerW * durationSec`와 일치
- dt가 0.1, 0.2, 0.5일 때 총 에너지 차이가 허용 오차 안인지 확인

### E-03. `intVal()`이 소수 입력을 조용히 절삭함 — P2

관련 파일:

- `src/main/java/com/wastesim/edge/EdgeArgs.java`

현재 구현은 `isNumber()`만 확인한 뒤 `asInt()`를 호출한다.

재현 입력:

```json
{
  "heatsink": {
    "finCount": 10.9
  }
}
```

위 값은 오류가 아니라 `10`으로 처리된다.

영향:

- 클라이언트가 보낸 값과 실제 계산값이 다르다.
- MCP 스키마의 `integer` 계약이 서버에서 강제되지 않는다.

권장 수정:

- `JsonNode.isIntegralNumber()`를 사용한다.
- 정수 범위 밖의 큰 값에 대한 overflow 여부도 확인한다.

권장 테스트:

- `finCount=10` 통과
- `finCount=10.1`, 문자열 `"10"` 거부

### E-04. 일부 누락된 `throttled` 샘플을 정상 상태로 간주함 — P2

관련 파일:

- `src/main/java/com/wastesim/edge/ThermalCalibrator.java`
- `src/main/java/com/wastesim/edge/CalibrateEdgeThermalModelTool.java`

샘플 하나에라도 `throttled` 값이 있으면 전체 열이 존재한다고 판단한다. 이후 개별 null 값은 다음 코드 때문에 false가 된다.

```java
boolean th = Boolean.TRUE.equals(s.throttled());
```

영향:

- 스로틀링 에피소드 중간에 측정값 하나가 비면 그 시점에서 에피소드가 종료된다.
- TED가 짧아지고 TRT가 실제보다 빠르게 계산될 수 있다.

권장 수정 후보:

- null이면 직전 상태를 유지한다.
- 또는 해당 구간을 측정 불명으로 표시하고 TED/TRT 산출을 거부한다.
- 열 완전성 비율을 계산해 경고한다.

권장 테스트:

- `true, true, null, true, false` 시퀀스에서 null이 에피소드를 끊지 않는지 확인
- throttle 값이 거의 없는 데이터는 지표 산출을 거부하는지 확인

### E-05. 보정 CSV의 `NaN`과 `Infinity`가 차단되지 않음 — P2

관련 파일:

- `src/main/java/com/wastesim/edge/CalibrateEdgeThermalModelTool.java`
- `src/main/java/com/wastesim/edge/ThermalCalibrator.java`
- `src/main/java/com/wastesim/edge/EdgeArgs.java`

Java의 `Double.parseDouble()`은 `NaN`, `Infinity`를 허용한다. 배열 입력도 유한성 검사가 명시적으로 보이지 않는다.

영향:

- 최대 온도, 평균 전력, 지수 피팅, R², RMSE가 NaN으로 오염될 수 있다.
- `d < min || d > max` 비교는 NaN에 대해 모두 false이므로 범위 검증을 우회할 수 있다.

권장 수정:

- 모든 실수 입력에 `Double.isFinite(value)` 검사를 먼저 적용한다.
- CSV 행 번호와 필드명을 포함한 구조화된 오류를 반환한다.

권장 테스트:

- 온도·시간·전력의 `NaN`, `Infinity`, `-Infinity` 거부
- 정상적인 과학 표기 `1.2e2` 처리 정책 확인

### E-06. R1의 `TRT_service` 의미가 실제 FPS 복원과 다름 — P3

관련 파일:

- `src/main/java/com/wastesim/edge/ThermalSimulator.java`
- `src/main/java/com/wastesim/edge/ThermalRun.java`

R1은 워크로드를 완전히 중지하므로 실제 FPS는 0이다. 하지만 `TRT_service`는 실제 `fps`가 아니라 `achievableFps`가 기준 이상인지로 판정한다.

현재 notes에서는 이를 “재개한다면 가능한 FPS”로 설명하지만 `ThermalRun` 필드 문서는 “FPS가 기준의 90% 이상으로 복원”이라고 되어 있어 의미가 다르다.

권장 수정 선택지:

1. 필드 이름을 `trtServiceCapacitySec`처럼 변경하여 잠재 처리능력 복원임을 명시한다.
2. 실제 FPS 복원을 의미한다면 R1에서는 null로 둔다.
3. 잠재 처리능력과 실제 서비스 FPS 복원을 별도 지표로 제공한다.

**해소 (2026-08-26, D-37 — 선택지 3 채택).** 세 선택지 중 3을 골랐다. 1은 공개된 MCP 응답 계약을
깨고, 2는 R1에서 관측 가능한 정보(클럭이 언제 풀렸는가)를 버린다. 두 지표는 실제로 서로 다른
질문에 답하므로 하나를 고르는 대신 나눴다.

- `trtServiceCapacitySec` — 잠재 처리능력 회복(기존 계산과 같은 값)
- `trtObservedServiceSec` — 실측 FPS 회복. R1(추론 중지)·R2(저부하 25%)에서는 **null**이며,
  그 null이 곧 답이다. 빈자리를 잠재 처리능력으로 채우지 않는다(D-26 조용한 보정 금지)
- `trtServiceSec` — 값을 유지한 채 **deprecated**. 제거 시점은 클라이언트 이전 후 별도로 정한다

회귀 테스트는 `RecoveryMetricSemanticsTest`(5건)다.

### E-07. 열 모델 파라미터 유효성 방어를 강화할 필요가 있음 — P2

관련 파일:

- `src/main/java/com/wastesim/edge/ThermalParams.java`
- `src/main/java/com/wastesim/edge/HeatsinkMass.java`

`ThermalParams`는 public record라 도구 계층을 우회한 테스트·내부 호출에서 비물리적 값이 들어갈 수 있다.

검증 권장 항목:

- `rJaKPerW > 0`
- `cThJPerK > 0`
- 전력은 유한한 0 이상
- `maxClockMhz > 0`
- `0 < minClock <= softFloorClock <= maxClock`
- `softLimitC < hardLimitC`
- `hysteresisC >= 0`
- 모든 double 값이 finite인지

record compact constructor에서 방어하면 모든 생성 경로에 동일한 불변식을 적용할 수 있다.

### E-08. 방열판 접촉률 극소 구간에서 계산 입력이 서로 불일치함 — P3

관련 파일:

- `src/main/java/com/wastesim/edge/HeatsinkThermalModel.java`

접촉률이 1% 이하이면 `coverage`는 1%로 올리지만 `contactM2`는 실제 겹침 면적을 별도로 사용한다. 따라서 `rMisalign`은 1% 접촉으로 계산하면서 `rTim`과 `rSpread`는 더 작은 실제 접촉 면적으로 계산할 수 있다.

이는 안전 측 근사일 수 있지만 모델의 동일한 “접촉 면적” 개념에 서로 다른 값이 사용된다.

권장 수정:

- 계산용 coverage와 보고용 실제 coverage를 분리한다.
- 1% 이하를 계산 가능한 상태로 둘지, 물리적으로 무효한 배치로 거부할지 정책을 명시한다.

**해소 (2026-08-26, D-38 — 분리 채택, 거부하지 않음).** `reportedCoverage`(실제 겹침)와
`effectiveCoverage`(모델 유효 하한 1% 적용)를 나누고, `rTim`·`rMisalign`·`rSpread`가 **전부**
`effectiveCoverage` 하나를 참조하게 했다. 하한 위 구간에서는 두 값이 같으므로 기존 결과는 바뀌지 않는다.

거부(`INVALID_LAYOUT`)하지 않기로 한 근거는 셋이다.

1. 방열판을 옆으로 밀어 붙인 배치는 **실제로 만들 수 있는** 형상이다 — "핀 두께 합이 베이스 폭보다
   크다" 같은 존재 불가 형상과 달리 거부할 물리적 근거가 없다.
2. 이 도구의 질문 자체가 "오프셋을 주면 얼마나 나빠지는가"인데, 곡선의 끝을 거부하면 끝까지 답할 수 없다.
3. 하한을 적용한 결과는 "방열판이 없는 것과 다름없다"로 수렴하고, 그것이 이 구간에서 물리적으로 맞는 답이다.

다만 **겹침이 정확히 0인 배치는 하한 대상이 아니다.** "아주 조금 닿았다"와 "아예 안 닿았다"는 다른
상태이고, 접촉 경로가 없으면 R_ja는 무냉각과 같아야 한다 — 하한을 먹이면 없는 접촉면을 만들어 내
무냉각보다 좋은 값이 나온다(구현 중 실제로 이 회귀가 났고 기존 테스트가 잡았다).

응답에는 `reportedCoverage`·`effectiveCoverage`·`coverageFloorApplied`·`effectiveContactAreaMm2`가
함께 실린다. 회귀 테스트는 `HeatsinkCoverageFloorTest`(6건)다.

### E-09. 냉각 부정 어휘에 "냉각장치"가 없어 무냉각 질문이 방열판 실행으로 바뀜 — P1

관련 파일:

- `src/main/java/com/wastesim/edge/EdgeParamGuard.java`

발견 경로는 UI 라이브 실행이다. 사용자가 이렇게 물었다.

> 방열판, 냉각장치 없이 pi4로 주변 온도 28도로 3시간 돌리면 스로틀링 언제 발생해

화면에는 **냉각 조건이 "방열판"으로 표시되고 정상상태 63.7℃**가 나왔다. 사용자가 물은 무냉각과
다른 실험이 조용히 실행된 것이다.

원인은 로직이 아니라 **어휘 하나**다. 목록형 부정을 잡는 `BOTH_ABSENT`가 능동 냉각 부품으로
`팬`·`쿨러`·`fan`만 알고 있어서 "냉각장치"에서 매칭이 끊겼다. `방열판\s*없`도 사이에 쉼표가
있어 걸리지 않았다. 남은 것은 `PASSIVE`의 "방열판" 한 단어뿐이라 passive로 확정됐다.
같은 문장에서 `냉각장치`를 `쿨러`로만 바꾸면 정상적으로 bare가 나왔다.

**왜 P1인가.** 결과가 틀린 것이 아니라 **묻지 않은 질문에 답한다**. 63.7℃는 방열판을 단 Pi4의
정확한 값이고 경고도 뜨지 않으므로, 사용자는 자기 질문의 답으로 읽는다. 게다가 이 조건에서는
소프트 제한(80℃)조차 닿지 않아 **보고할 시점 자체가 생기지 않는다** — "스로틀링 언제 발생하냐"는
질문에 아무 시각도 못 주고 끝난다. 무냉각으로 제대로 돌리면 답이 나온다.

| 냉각 | 정상상태 | 소프트 제한 진입 | 하드 스로틀링 |
|---|---|---|---|
| passive(실제 실행된 것) | 63.7℃ | 없음 | 없음 |
| bare(질문한 것) | 84.5℃ | **219초** | 없음(84.5 < 85.0) |

이 조건은 하드 한계보다 **0.5℃ 낮은** 아슬아슬한 지점이라, 주변 온도 30℃면 TTT 507초로 뒤집힌다
(29℃까지는 미발생). 어휘 하나가 이 경계 전체를 가렸다.

**수정.** `COOLER` 어휘를 신설해 `냉각장치`·`냉각모듈`·`냉각기`를 팬·쿨러와 같은 자리에 넣었다.
`장치`·`모듈`은 단독으로 쓰면 범위가 너무 넓어("측정장치 없이"가 무냉각 실행으로 둔갑한다)
**"냉각"이 앞에 올 때만** 냉각 부품으로 인정한다.

단독으로 "냉각장치 없이"만 쓰면 기존 "팬 없이" 규칙 그대로 **passive를 유지한다** — 이 말만으로는
"능동 냉각만 빼겠다"인지 "아무것도 안 붙이겠다"인지 알 수 없고, 방열판까지 함께 부정해야 bare다.

회귀 테스트는 `CoolingNegationTest`에 3건 추가했다(스크린샷 문장 원문 포함).

**남는 교훈.** 이것은 D-33(도메인 게이트의 rpm·pwm·회전수·운전점 누락)과 **같은 종류의 결함**이다.
로직·테스트가 모두 정상인데 사용자가 실제로 쓰는 단어가 목록에 없어서, 오류 없이 *다른 실험*이
실행된다. 두 건 모두 정적 검토가 아니라 라이브 실행에서만 드러났다 — 어휘 목록은 코드 리뷰로
빈틈을 찾기 어렵고, 실제 문장을 넣어 봐야 보인다. 새 어휘를 추가할 때는 그 개념의 **동의어까지**
함께 넣었는지 확인한다(팬 → 쿨러 → 냉각장치 → 냉각모듈).

---

## 5. 현재 사용자 변경 사항 검토

현재 작업 트리에는 다음 변경이 존재한다.

- `src/main/java/com/wastesim/edge/EdgeToolSupport.java`
- `src/test/java/com/wastesim/edge/FanModelTest.java`

변경 목적은 `cooling=active`만 지정해도 기본 정격 팬을 생성하여 팬 소비전력을 집계하는 것이다.

검토 결과:

- `cooling=active`와 명시적 정격 RPM 입력이 같은 팬 전력으로 계산되는 의도는 타당하다.
- 명시적 `fanRpm`이 기본값보다 우선하는 것도 적절하다.
- passive/bare 냉각에 팬 입력이 없으면 팬 객체를 만들지 않는 기존 동작이 유지된다.
- 기존 `target/surefire-reports/com.wastesim.edge.FanModelTest.txt`에는 12개 테스트 성공 기록이 있다.

추가 권장 테스트:

- `cooling=active, fanRpm=0`이 명시적 팬 정지 및 passive 열저항으로 해석되는지 계약 고정
- `cooling=active, fanRatedPowerW=0` 허용 정책 확인
- 명시적 `rJaKPerW` 또는 `thermalOverride`가 있을 때 팬 전력은 집계하되 열저항은 덮어쓰지 않는지 확인

---

## 6. 수정 권장 순서

### 1단계: 결과를 직접 왜곡하는 P1 수정 — ✅ 완료

1. ✅ E-01: R2 + MAX_THROUGHPUT 저부하 적용
2. ✅ E-02: 종료 시각 추가 적분 제거
3. ✅ W-03: 비수거일 차량·교통 이벤트 제거
4. ✅ W-01: `wasteTypes` 내부 검증
5. ✅ W-02: 모든 수거 시각 범위 검증

### 2단계: 입력 경계 강화 — ✅ 완료

1. ✅ W-04: 엄격한 HH:MM 파싱 (`SimulationConfig.hhmmToMinutes` 정규식 파서, 시·분 각각 검증)
2. ✅ E-03: 정수 입력 소수 거부 (`EdgeArgs.intVal` `isIntegralNumber` + long 범위 가드)
3. ✅ E-05: NaN/Infinity 거부 (JSON은 `isNumber`가, 보정 CSV는 `CalibrateEdgeThermalModelTool.finite`가 차단)
4. ✅ W-05/W-06: 건물 수(≤26)와 확장 필드 검증 (`SimulationConfigValidator.validateExtendedFields` — 주기·공휴일·비율·시각·월별 가중치. setter의 조용한 보정 제거)
5. ✅ A-01: MCP required 필드 서버 측 강제 (`McpController`가 공개 스키마의 `required`를 실행 전에 검사 — `McpToolCatalog.missingRequired`)

### 3단계: 지표 의미와 모델 정책 정리 — 🔶 절반 완료

1. ✅ E-04: 불완전한 throttle 측정 처리 (`ThermalCalibrator.ThrottleState`가 THROTTLED/CLEAR/**UNKNOWN** 3상태 — 결측을 "정상"으로 뭉개지 않는다. `ThrottleMissingDataTest`)
2. E-06: TRT_service 의미 분리 — **완료**(2026-08-26, D-37 — 두 지표 분리 + 구 필드 deprecated, `RecoveryMetricSemanticsTest`)
3. ✅ E-07: `ThermalParams` 불변식 적용 (compact constructor에 `rJa>0`·`cTh>0`·`0 < minClock ≤ softFloor ≤ maxClock`·`softLimit < hardLimit`·유한성. `ThermalParamsInvariantTest`)
4. E-08: 극소 접촉률 계산 정책 결정 — **완료**(2026-08-26, D-38 — coverage 분리·거부하지 않음, `HeatsinkCoverageFloorTest`)

A-02(비교 API의 빈 `times`)도 이 단계에서 함께 해소했다 — 빈 배열·null은 400 VALIDATION으로 거부하고,
**미지정**만 종전대로 기본값 3종으로 실행한다(하위호환).

E-09(냉각 부정 어휘 "냉각장치" 누락)는 2026-08-26 라이브 실행에서 발견해 같은 날 수정했다
(`CoolingNegationTest`). 결과가 틀린 것이 아니라 묻지 않은 질문에 답하는 유형이라 P1으로 뒀다.

---

## 7. 최소 회귀 테스트 목록

수정 완료 판단을 위한 최소 테스트 세트:

- [x] 사용자 지정 폐기물 종류의 비율·용량·임계값 검증
- [x] 복수·주말 수거 시각 범위 검증
- [x] 격일 수거의 비수거일 교통 민원 0
- [x] 잘못된 HH:MM 입력 거부
- [x] 27개 이상 건물 처리 정책 검증
- [x] MCP required 필드 누락 거부
- [x] R2 + MAX_THROUGHPUT 전력 감소
- [x] 상수 전력 × 실행시간과 energyJ 정확히 일치
- [x] 팬 전력 × 실행시간과 fanEnergyJ 정확히 일치
- [x] 소수 finCount 거부
- [x] 중간 null throttle 샘플 처리 (3단계 E-04 — `ThrottleMissingDataTest`)
- [x] CSV NaN/Infinity 거부
- [x] R1/R2/R3 회복 지표 의미 검증 (3단계 E-06 — `RecoveryMetricSemanticsTest`)
- [x] 접촉률 0.1%~1% 구간 coverage 일관성 (3단계 E-08 — `HeatsinkCoverageFloorTest`)
- [x] 냉각 부정 어휘 동의어(냉각장치·냉각모듈) 인식 (E-09 — `CoolingNegationTest`)
- [x] 1노드와 2노드 모델의 정상상태 온도 일치 (`TwoNodeThermalModelTest.steadyStateMatchesOneNodeModel`)
- [x] dt 변화에 따른 온도·에너지 수렴성 확인 (`ThermalSimulatorAccuracyTest`)

## 8. 테스트 실행 환경 메모

정상적인 개발 환경에서 다음 명령으로 전체 회귀 테스트를 다시 실행해야 한다.

```powershell
cd C:\Dev\MCP\waste-sim-spring
mvn clean test
```

Maven이 PATH에 없다면 Maven 설치 또는 Maven Wrapper(`mvnw`, `mvnw.cmd`) 추가를 권장한다. Wrapper를 저장소에 포함하면 개발자와 CI가 같은 Maven 버전으로 테스트할 수 있다.
