# 팬 RPM 스윕·최적점 탐색 설계

## 1. 문서 상태와 목적

이 문서는 냉각팬의 PWM/RPM을 자동으로 변화시키며 엣지 열 시뮬레이션을 반복하고,
성능·온도 제약을 만족하는 지점 중 에너지 비용이 가장 낮은 운전점을 찾는 기능을 정의한다.

이 설계는 **구현 완료**되었다(별도 MCP 도구 `sweep_fan_rpm`). 이 문서는 그 요구사항·계산
기준·응답 형식·테스트 기준을 계속 기준 문서로 유지하며, 구현하면서 설계와 달라진 부분은
§17에 따로 기록한다.

핵심 연구 질문은 다음과 같다.

> 요구 FPS와 온도 한계를 지키면서 시스템 전체 에너지 또는 추론 1건당 에너지가 최소가 되는 팬 속도는 얼마인가?

---

## 2. 별도 MCP 도구로 분리하는 이유

`simulate_edge_throttling`은 한 조건에 대한 단일 실행 결과를 반환한다. 반면 RPM 스윕은
여러 실행 결과, 제약 판정, 최적점과 그래프 데이터를 반환한다.

두 기능을 하나의 도구에 섞으면 다음 문제가 생긴다.

- 입력 스키마가 단일 실행과 배열 실행을 동시에 표현해야 한다.
- 응답이 단일 `ThermalRun`인지 스윕 곡선인지 호출마다 달라진다.
- 채팅 포맷터와 UI가 응답 형식에 따라 계속 분기해야 한다.
- 단일 실행의 기존 MCP 계약을 변경하게 된다.

따라서 다음처럼 역할을 분리한다.

| 도구 | 역할 |
|---|---|
| `simulate_edge_throttling` | 지정된 팬 속도 한 점을 상세하게 시뮬레이션 |
| `sweep_fan_rpm` | 여러 PWM/RPM을 실행하고 제약조건 아래 최적점을 선택 |

`sweep_fan_rpm`은 내부적으로 기존 `ThermalSimulator`를 재사용하며 열 계산 공식을 복제하지 않는다.

---

## 3. 스윕 실행 방식

사용자가 PWM 범위와 단계 수를 입력하면 같은 보드·주변 온도·워크로드 조건으로 팬 속도만
변경하면서 반복 실행한다.

예를 들어 PWM 0~100%를 11개 지점으로 나누면 다음 값을 실행한다.

```text
0%, 10%, 20%, 30%, 40%, 50%, 60%, 70%, 80%, 90%, 100%
```

각 지점에서 최소한 다음 값을 수집한다.

- 명령 PWM과 유효 RPM
- RPM 출처: TACH 실측, 보정곡선, PWM 추정 또는 정격 가정
- 유효 열저항 `R_ja`
- 팬 소비전력과 팬 소비 에너지
- SoC 소비 에너지
- 시스템 총에너지
- 최고 온도
- 소프트 제한 진입 시각
- TTT·TED와 스로틀링 시간 비율
- 평균 FPS와 지속 처리량 손실률
- 총 처리 프레임 수 또는 처리량 적분값
- 제약조건 충족 여부와 탈락 사유

---

## 4. 공정한 비교를 위한 고정 조건

RPM별 총에너지를 비교하려면 모든 지점이 같은 시간 동안 같은 작업을 수행해야 한다.
스로틀링을 감지한 순간 회복 정책을 조기 적용하면 RPM에 따라 부하 구간이 달라져 결과를
직접 비교할 수 없다.

스윕의 기본 실행 조건은 다음과 같이 고정한다.

```text
recoveryPolicy = none
recoverySeconds = 0
applyRecoveryOnThrottle = false
loadSeconds = 모든 지점에서 동일
AI 부하 프로필 = 모든 지점에서 동일
초기 온도와 주변 온도 = 모든 지점에서 동일
```

회복 정책 자체를 연구하는 별도 스윕이 필요하다면 에너지 최적화 결과와 분리해 제공한다.

---

## 5. 제약조건

### 5.1 TTT만으로 정상 여부를 판단하면 안 되는 이유

`tttSec == null`만 검사하면 하드 스로틀링은 없지만 소프트 온도 제한으로 처리량을 잃는
조건도 정상으로 선택될 수 있다. 예를 들어 Pi4 무냉각 조건은 하드 제한에 도달하지 않고도
소프트 제한에 머물며 약 20%의 지속 처리량을 잃을 수 있다.

따라서 기본 적합 판정은 다음 조건을 함께 사용한다.

```text
tttSec == null
그리고 peakTempC <= maxPeakTempC
그리고 throughputLossPercent <= maxThroughputLossPercent
```

TARGET_FPS 모드에서는 다음 조건을 추가할 수 있다.

```text
meanFpsLoad >= targetFps × minTargetFpsRatio
```

권장 기본값은 다음과 같다.

| 제약 | 권장 기본값 |
|---|---:|
| `maxPeakTempC` | 85℃ |
| `maxThroughputLossPercent` | 1% |
| `minTargetFpsRatio` | 0.99 |
| 하드 스로틀링 허용 | 불허 |

### 5.2 적합 여부 판정

각 스윕 지점은 다음 상태 중 하나를 갖는다.

| 상태 | 의미 |
|---|---|
| `FEASIBLE` | 모든 제약조건 충족 |
| `HARD_THROTTLED` | TTT가 발생했거나 하드 스로틀링 시간 비율이 0보다 큼 |
| `TOO_HOT` | 최고 온도가 한계를 초과 |
| `THROUGHPUT_LOSS` | 지속 처리량 손실이 허용 범위를 초과 |
| `TARGET_FPS_MISSED` | 목표 FPS 유지 실패 |
| `UNVERIFIED_INPUT` | 팬 사양의 불확실성 때문에 확정 판정 불가 |

한 지점에 여러 탈락 사유가 있을 수 있으므로 결과에는 코드 배열로 보존한다.

---

## 6. 운용 모드별 목적함수

### 6.1 TARGET_FPS

모든 지점이 같은 목표 FPS와 실행시간을 만족하므로 처리한 작업량이 거의 같다. 이때는
시스템 총에너지를 최소화하면 된다.

```text
목적함수 = totalEnergyJ 최소
```

`totalEnergyJ`는 다음 항목의 합이다.

```text
SoC 소비 에너지
+ 팬 소비 에너지
+ 지속시간을 아는 경우 팬 기동 에너지
```

### 6.2 MAX_THROUGHPUT

최대 처리량 모드에서는 냉각을 강화할수록 더 높은 클럭과 더 많은 FPS를 유지할 수 있다.
총에너지만 최소화하면 적은 일을 수행한 저RPM 지점이 유리해질 수 있다.

따라서 기본 목적함수는 추론 프레임당 에너지로 한다.

```text
processedFrames = ∫ FPS dt
energyPerFrameJ = totalEnergyJ / processedFrames
목적함수 = energyPerFrameJ 최소
```

대안으로 최소 처리량 조건을 먼저 적용한 뒤 `totalEnergyJ`를 최소화할 수도 있다.

```text
processedFrames >= minProcessedFrames
또는 meanFpsLoad >= minMeanFps
```

### 6.3 최적점 동률 처리

목적함수 값이 허용 오차 안에서 같으면 다음 순서로 선택한다.

1. PWM이 낮은 지점
2. 유효 RPM이 낮은 지점
3. 최고 온도가 낮은 지점

동률 기준과 허용 오차는 응답에 남겨 재현 가능하게 한다.

---

## 7. 입력 스키마 제안

```json
{
  "board": "pi5",
  "cooling": "passive",
  "ambientTempC": 35,
  "workloadMode": "target_fps",
  "targetFps": 15,
  "loadSeconds": 3600,
  "aiLoadProfileId": "steady",
  "fanArray": {
    "presetId": "PI5_DUAL_40MM_PRELIMINARY"
  },
  "sweep": {
    "minPwmPercent": 0,
    "maxPwmPercent": 100,
    "steps": 11
  },
  "constraints": {
    "maxPeakTempC": 85,
    "maxThroughputLossPercent": 1,
    "minTargetFpsRatio": 0.99
  },
  "objective": "min_total_energy",
  "includeSeriesForOptimal": true
}
```

### 7.1 스윕 입력

| 필드 | 의미 | 권장 범위 |
|---|---|---|
| `minPwmPercent` | 시작 PWM | 0~100 |
| `maxPwmPercent` | 종료 PWM | 0~100 |
| `steps` | 시작·종료를 포함한 실행 지점 수 | 2~101 |

향후에는 사용자가 직접 RPM 배열을 전달하는 형식도 추가할 수 있다.

```json
{
  "rpmPoints": [0, 1500, 2500, 3500, 5000]
}
```

PWM 스윕과 직접 RPM 배열을 동시에 입력하면 모호하므로 거부한다.

### 7.2 목적함수 enum

```text
min_total_energy
min_energy_per_frame
min_fan_energy
min_pwm
```

`min_fan_energy`와 `min_pwm`은 온도·처리량 제약을 먼저 만족한 지점 안에서만 적용한다.

### 7.3 입력 검증

- `minPwmPercent <= maxPwmPercent`
- `steps >= 2`
- 생성된 PWM 값은 중복되지 않아야 함
- 팬 배열과 기존 단일 `fanRpm` 입력 동시 사용 금지
- 팬을 지정했지만 방열판·냉각판이 없으면 FR-96으로 거부
- MAX_THROUGHPUT에서 `min_total_energy`를 요청하면 경고 또는 명시적 허용 플래그 요구
- NaN·Infinity와 잘못된 enum 거부

---

## 8. 실행 알고리즘

```text
1. 공통 입력과 팬 사양을 검증한다.
2. PWM/RPM 스윕 지점을 생성한다.
3. 각 지점에 동일한 열·부하·시간 조건을 복사한다.
4. 해당 PWM에서 유효 RPM과 팬 전력을 계산한다.
5. ThermalSimulator를 실행한다.
6. 처리 프레임 수를 FPS 시간 적분값으로 계산한다.
7. 온도·TTT·처리량 제약을 평가한다.
8. 적합 지점만 목적함수로 정렬한다.
9. 동률 규칙을 적용해 최적점을 선택한다.
10. 모든 지점, 최적점, 경고와 차트 데이터를 반환한다.
```

모든 지점을 부적합으로 판정한 경우 최적점을 억지로 선택하지 않는다.

```json
{
  "optimal": null,
  "status": "NO_FEASIBLE_POINT",
  "recommendation": "팬 사양·PWM 범위를 확대하거나 목표 FPS·주변 온도 조건을 조정할 것"
}
```

---

## 9. 응답 형식 제안

```json
{
  "tool": "sweep_fan_rpm",
  "status": "OPTIMAL_FOUND",
  "objective": "min_total_energy",
  "optimal": {
    "commandedPwmPercent": 50,
    "effectiveRpm": 3875,
    "rpmSource": "PWM_ESTIMATE",
    "peakTempC": 78.0,
    "tttSec": null,
    "throughputLossPercent": 0.0,
    "processedFrames": 54000,
    "energyPerFrameJ": 0.5,
    "socEnergyJ": 26500,
    "fanEnergyJ": 500,
    "totalEnergyJ": 27000,
    "reason": "온도·처리량 제약을 만족하는 지점 중 총에너지 최소"
  },
  "points": [
    {
      "commandedPwmPercent": 0,
      "effectiveRpm": 0,
      "fanPowerW": 0,
      "peakTempC": 85.0,
      "tttSec": 420,
      "throughputLossPercent": 18.0,
      "totalEnergyJ": 25000,
      "feasible": false,
      "rejectionReasons": ["HARD_THROTTLED", "THROUGHPUT_LOSS"]
    }
  ],
  "constraints": {
    "maxPeakTempC": 85,
    "maxThroughputLossPercent": 1,
    "minTargetFpsRatio": 0.99
  },
  "fanSpec": {
    "source": "PRELIMINARY_ESTIMATE",
    "verified": false,
    "measurementScope": "UNKNOWN_PER_FAN_OR_TOTAL"
  },
  "notes": [
    "FAN_SPEC_NOT_VERIFIED",
    "RPM_ESTIMATED_FROM_PWM"
  ]
}
```

`points`에는 최적점뿐 아니라 모든 실행 결과를 남긴다. 그래야 사용자가 최적점 선택을
검증하고 발표 그래프를 다시 만들 수 있다.

---

## 10. 그래프 설계

발표용 기본 그래프는 다음 구성을 사용한다.

- X축: 유효 RPM 또는 명령 PWM
- 왼쪽 Y축: 총에너지 또는 프레임당 에너지
- 오른쪽 Y축: 최고 온도와 처리량 손실률
- 붉은 배경: 하드 스로틀링 발생 구간
- 노란 배경: 소프트 제한·처리량 조건 미달 구간
- 초록 표시: 선택된 최적 운전점

곡선 데이터는 브라우저에서 바로 그릴 수 있도록 숫자 배열로 반환하고, 서버가 이미지 파일을
만드는 방식은 사용하지 않는다.

---

## 11. 팬 사양 불확실성 처리

Pi5 2팬 프리셋 `PI5_DUAL_40MM_PRELIMINARY`는 검증 전 임시 사양이다. 이 사양으로 찾은
최적점은 확정값이 아니라 잠정 결과로 표시해야 한다.

정확도 우선순위는 다음과 같다.

```text
PWM 기반 RPM·전력 추정
→ TACH 실측 RPM
→ 실측 전류
→ PWM-RPM 보정곡선
→ RPM별 열저항 실측 보정
```

응답에는 항상 다음 출처 정보를 포함한다.

- `fanSpecVerified`
- `rpmSource`
- `fanSpecSource`
- `measurementScope`
- 팬 관련 경고 코드

팬 기동 지속시간을 모르면 기동 피크 전력을 전체 실행시간 동안 지속되는 값으로 계산하지 않는다.
기동 에너지는 총에너지에서 제외하고 `STARTUP_ENERGY_NOT_INCLUDED` 경고를 반환한다.

---

## 12. 면적·배치 효율 판정의 한계

RPM 스윕만으로 “팬이 방열판 면적의 절반만 덮을 때 냉각 효율이 80%인가”를 판정할 수는 없다.
현재 `FanArraySpec`의 팬 위치·거리·송풍 방향은 실험 조건을 보존하는 메타데이터이며,
실측 보정 전에는 냉각계수를 변경하지 않는다.

면적·배치 효율까지 비교하려면 다음 데이터가 추가로 필요하다.

- 팬과 방열판의 겹침 면적
- 팬과 방열판 사이 거리
- 송풍·배기·수평 흐름 방향
- 방열판 표면의 실제 풍속 분포
- 조건별 실측 `R_ja`
- 동일 부하에서의 SoC·방열판 온도 시계열

따라서 RPM 최적점은 PTM의 팬 속도 목표값으로 사용할 수 있지만, 면적 효율 주장은 별도의
배치 실험과 보정이 끝난 뒤 결합해야 한다.

> **관련 도구:** `rank_fan_layouts`(FR-115~118)가 팬 위치·방향 조합을 경험적 점수로
> 순위 매기지만, 그 도구도 이 절의 한계를 해소하지 않는다 — 겹침 면적·거리·풍속 분포·
> 조건별 실측 `R_ja` 없이 만든 임시 계수이고, 열 스택과 격리돼 있어 여기 계산에
> 관여하지 않는다(D-43). 면적 효율 주장은 여전히 실측 보정 이후의 일이다.

---

## 13. 예상 구현 파일

| 파일 | 역할 |
|---|---|
| `SweepFanRpmTool.java` | MCP 입력 검증, 스윕 실행과 응답 생성 |
| `FanSweepPoint.java` | 지점별 결과와 탈락 사유 |
| `FanSweepResult.java` | 최적점·전체 곡선·조건·제약과 선택 규칙 |
| `EdgeToolSelector.java` | 자연어 요청을 스윕 도구로 라우팅 |
| `EdgeChatFormatter.java` | 최적점과 구간별 요약 출력 |
| `ChatController.java` · `ChatMessage.java` | 채팅 경로와 `EDGE_SWEEP` 메시지 타입 |
| `edge.js` · `app.css` | RPM-에너지-온도 곡선 렌더링 |
| `FanRpmSweepTest.java` | 최적점·제약·경계 회귀 테스트 |

기존 `FanSpec`, `FanArraySpec`, `EdgeToolSupport`, `ThermalSimulator`의 계산을 재사용한다.
`McpToolRegistry`는 손대지 않았다 — 스프링이 `McpToolProvider` 구현체를 모두 주입하므로
`@Component` 하나로 `tools/list`와 `tools/call`에 자동 등록된다.

---

## 14. 회귀 테스트

### 14.1 스윕 생성

- 0~100%, 11단계가 정확히 11개 지점을 생성하는지 확인
- 시작·종료 값이 포함되는지 확인
- 잘못된 범위와 2 미만 단계 수를 거부하는지 확인

### 14.2 제약 판정

- TTT가 발생한 지점이 탈락하는지 확인
- TTT가 없어도 처리량 손실 한도를 넘으면 탈락하는지 확인
- 목표 FPS의 99% 미만이면 탈락하는지 확인
- 모든 지점이 탈락하면 `optimal=null`인지 확인

### 14.3 목적함수

- TARGET_FPS에서 적합 지점 중 총에너지가 가장 작은 지점을 선택하는지 확인
- MAX_THROUGHPUT에서 프레임당 에너지가 가장 작은 지점을 선택하는지 확인
- 총에너지가 낮아도 처리량이 부족한 지점을 선택하지 않는지 확인
- 동률이면 낮은 PWM을 선택하는지 확인

### 14.4 에너지와 팬 사양

- 팬 전력이 SoC 온도 적분에 들어가지 않는지 확인
- 실측 전류가 있으면 `V × I`가 팬 전력으로 사용되는지 확인
- 배열 전체 정격값을 팬 개수로 다시 곱하지 않는지 확인
- 기동 지속시간이 없으면 기동 에너지가 총합에 들어가지 않는지 확인

### 14.5 재현성과 호환성

- 같은 입력은 같은 최적점을 반환하는지 확인
- 단일 `simulate_edge_throttling` 결과와 동일 PWM 지점의 값이 일치하는지 확인
- 기존 단일 팬 입력을 팬 1개 배열로 변환해도 결과가 유지되는지 확인
- 기존 전체 테스트가 계속 통과하는지 확인

---

## 15. 구현 단계

1. `ThermalRun` 또는 별도 결과에 `processedFrames`를 노출한다.
2. 스윕 지점과 결과 record를 정의한다.
3. `SweepFanRpmTool`에서 고정 조건으로 반복 실행한다.
4. 제약 평가와 목적함수별 최적점 선택기를 구현한다.
5. MCP 레지스트리와 도구 카탈로그에 등록한다.
6. 자연어 라우팅과 채팅 결과 포맷터를 추가한다.
7. 브라우저 UI에 에너지·온도·스로틀링 곡선을 추가한다.
8. 회귀 테스트와 문서를 갱신한다.

---

## 16. 완료 기준

- [x] 사용자가 PWM 범위와 단계 수를 지정할 수 있다.
- [x] 모든 지점이 동일한 시간·부하·초기조건으로 실행된다.
- [x] TTT뿐 아니라 온도·처리량 손실·목표 FPS를 제약으로 평가한다.
- [x] TARGET_FPS는 총에너지, MAX_THROUGHPUT는 프레임당 에너지로 기본 최적화한다.
- [x] 적합 지점이 없으면 최적점을 만들지 않는다.
- [x] 모든 스윕 지점과 탈락 사유가 응답에 포함된다.
- [x] 팬 사양의 실측·추정 여부와 경고가 결과에 보존된다.
- [x] UI에서 RPM-에너지-온도 곡선과 스로틀링 구간을 표시한다.
- [x] 단일 실행 결과와 동일 RPM 지점의 결과가 일치한다.
- [x] 신규 테스트와 기존 전체 테스트가 모두 통과한다(전체 351개).

---

## 17. 구현하며 설계와 달라진 점

### 17.1 한 운전점의 실측값은 스윕에서 거부한다 (설계에 없던 규칙)

`measuredArrayRpm`(TACH)과 `measuredCurrentA`는 §11의 정확도 우선순위에서 PWM 추정보다
위에 있다. 그런데 이 값들은 **한 운전점에서 잰 값**이라, 그대로 두고 PWM을 훑으면
`FanArraySpec`이 모든 지점에서 같은 회전수·같은 전력을 돌려준다. 곡선이 평평해지고 그
위에서 고른 "최적점"은 동률 규칙이 뽑은 첫 지점일 뿐인데, 사용자는 실측을 반영한 결과라고
읽게 된다. 그래서 스윕 입력에서는 두 필드를 fail-closed로 거부하고, 필요한 것이
PWM-RPM 보정곡선(미구현)임을 오류 메시지로 알려준다.

### 17.2 `UNVERIFIED_INPUT`은 탈락 사유로 쓰지 않는다

§5.2의 상태 중 이 하나만 구현하지 않았다. 현재 기본 팬 사양이 검증 전
(`PI5_DUAL_40MM_PRELIMINARY`)이라 탈락 사유로 쓰면 **모든 지점이 항상 탈락**해 도구가
아무 답도 못 낸다. 대신 §11 그대로 `fanSpec.verified=false`·`FAN_SPEC_NOT_VERIFIED`
경고와 "잠정 결과" 주석으로 불확실성을 보존한다.

### 17.3 `rpmPoints`를 함께 구현했다

§7.1에서 "향후"로 미뤄 둔 형식인데, 정격 대비 비율로 PWM에 대응시키면 되는 작은 변환이라
같이 넣었다. `sweep`과 동시 입력 거부, 정격 초과 회전수 거부는 설계대로다.

### 17.4 최대 처리량 + 총에너지는 경고가 아니라 명시적 허용을 요구한다

§7.3이 "경고 또는 명시적 허용 플래그"를 선택지로 뒀는데, 이 프로젝트의 fail-closed
원칙에 맞춰 `allowTotalEnergyInMaxThroughput=true` 없이는 거부하는 쪽을 택했다. 경고는
읽히지 않을 수 있고, 이 조합은 "덜 일한 지점이 이기는" 잘못된 결론으로 바로 이어진다.

### 17.5 채팅 응답은 `EDGE_SWEEP` 메시지 타입으로 따로 보낸다

기존 `EDGE_RESULT`(`edgeRuns` 목록)와 형태가 달라 같은 필드에 담으면 클라이언트가 모양을
보고 추측해야 한다. 곡선 전용 타입과 `edgeSweep` 필드를 따로 뒀다.

