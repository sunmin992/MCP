# 엣지 열 모델 1순위 보완 계획

## 1. 문서 목적

이 문서는 엣지 열·스로틀링 시뮬레이션 결과의 정확도에 직접 영향을 주는 다음 세 가지 보완 작업을 구현 가능한 수준으로 정리한다.

1. 열 보정 데이터의 `throttled` 결측값 처리
2. FPS 및 지속 처리량 손실의 시간 가중 계산
3. 종료 시각의 클럭 상태 처리

대상 코드는 다음과 같다.

- `src/main/java/com/wastesim/edge/ThermalCalibrator.java`
- `src/main/java/com/wastesim/edge/CalibrateEdgeThermalModelTool.java`
- `src/main/java/com/wastesim/edge/ThermalSimulator.java`
- `src/main/java/com/wastesim/edge/ThermalRun.java`
- 관련 엣지 모델 테스트

---

## 2. 열 보정 결측값 처리

### 2.1 현재 문제

현재 `ThermalCalibrator`는 다음 코드로 스로틀링 상태를 읽는다.

```java
boolean th = Boolean.TRUE.equals(s.throttled());
```

이 방식에서는 `throttled == null`이 `false`가 된다. 따라서 다음 시계열은 실제 측정 의미와 다르게 해석된다.

```text
시각:          10s   20s   30s   40s
throttled:     true  null  true  false
현재 해석:      ON    OFF   ON    OFF
실제 의미:      ON   UNKNOWN ON    OFF
```

결과적으로 다음 문제가 발생할 수 있다.

- 하나의 스로틀링 에피소드가 두 개로 분리된다.
- TED가 실제보다 짧게 계산된다.
- 회복 구간의 결측값이 스로틀링 해제로 오인되어 `trtStateSec`가 빨리 생성된다.
- 결측 데이터가 많은 실험도 정상 데이터처럼 보인다.

### 2.2 권장 처리 정책

공식 지표 계산에는 `TRUE`, `FALSE`, `UNKNOWN`의 3상태를 사용한다.

| 입력 | 내부 상태 | 지표 계산 의미 |
|---|---|---|
| `true` | `THROTTLED` | 스로틀링이 명시적으로 관측됨 |
| `false` | `CLEAR` | 스로틀링 해제가 명시적으로 관측됨 |
| `null` | `UNKNOWN` | 상태를 판단할 수 없음 |

이전 상태를 화면 표시 목적으로 이어 그릴 수는 있지만, 그 보간값을 공식 TTT·TED·TRT 계산에 사용해서는 안 된다.

### 2.3 지표별 처리 규칙

#### TTT

- 명시적인 `true` 샘플부터 후보 구간을 시작한다.
- 확인 구간 안에 `null`이 나오면 연속 관측이 깨진 것으로 처리한다.
- `null` 구간을 건너뛰어 TTT를 확정하지 않는다.

#### TED

- 시작과 종료가 각각 명시적인 `true`, `false`로 확인된 에피소드만 완결 에피소드로 계산한다.
- 에피소드 중간에 `null`이 포함되면 해당 에피소드를 `incomplete`로 표시한다.
- 불완전 에피소드는 `medianTedSec` 계산에서 제외한다.
- 사용자에게 제외된 에피소드 수를 경고한다.

#### TRT

- 회복 시작 이후 명시적인 `false`가 필요한 확인 시간 동안 연속 관측돼야 한다.
- 확인 구간에 `null`이 나오면 해제 확인 타이머를 초기화한다.
- 결측 구간을 스로틀링 해제로 간주하지 않는다.

### 2.4 결측률과 측정 공백 경고

다음 품질 정보를 계산한다.

```text
missingThrottleSamples = throttled가 null인 샘플 수
missingThrottleRatio   = missingThrottleSamples / 전체 샘플 수
longestUnknownGapSec   = 연속 UNKNOWN 구간의 최대 시간
incompleteEpisodes     = 결측 때문에 지표에서 제외된 에피소드 수
```

권장 경고 기준은 다음과 같다.

- 결측값이 하나라도 있으면 결측 개수와 비율을 알린다.
- 결측률이 5% 이상이면 TTT·TED·TRT 신뢰도가 낮다는 경고를 추가한다.
- 연속 측정 공백이 기본 샘플 간격의 2배를 넘으면 공백 시작·종료 시각을 알린다.
- 불완전 에피소드가 있으면 `medianTedSec`에서 제외됐음을 알린다.

경고 예시는 다음과 같다.

```text
throttled 측정값 120개 중 8개(6.7%)가 누락됐다.
가장 긴 미확정 구간은 42.0~48.0초(6.0초)다.
결측값이 포함된 스로틀링 에피소드 1개를 TED 중앙값 계산에서 제외했다.
```

### 2.5 입력 단계 보완

`CalibrateEdgeThermalModelTool`에서도 잘못된 값을 결측값으로 조용히 바꾸지 않아야 한다.

- JSON의 `throttled`는 실제 boolean 타입만 허용한다.
- CSV는 `true`, `false`, `1`, `0` 및 지원하는 비트 표현만 허용한다.
- 알 수 없는 문자열은 `null`로 바꾸지 말고 행 번호가 포함된 입력 오류로 반환한다.
- 빈 셀은 허용하되 명시적인 `UNKNOWN`으로 전달한다.

### 2.6 회귀 테스트

- `true, true, null, true, false`에서 완결 TED를 만들지 않는지 확인
- 회복 구간의 `null`이 `trtStateSec`를 생성하지 않는지 확인
- `false`가 확인 시간 동안 연속된 경우에만 TRT가 생성되는지 확인
- 결측 개수·비율·최장 공백 경고가 정확한지 확인
- 잘못된 CSV boolean 값에 행 번호가 포함된 오류가 반환되는지 확인

---

## 3. FPS·처리량 손실 시간 가중 계산

### 3.1 현재 문제

현재 부하 구간 평균은 반복 횟수를 기준으로 계산한다.

```java
fpsIdealSum += idealFps;
fpsSum += fps;
fpsSamples++;
```

시뮬레이터는 종료 시간을 정확히 맞추기 위해 마지막 구간에 부분 스텝을 사용할 수 있다.

```text
dt = 0.5초
남은 시간 = 0.1초
```

현재 계산에서는 0.1초 구간도 0.5초 구간과 같은 가중치를 갖는다. 특히 마지막 구간에서 스로틀링 상태가 바뀌면 `meanFpsLoad`와 `throughputLossPercent`가 실제 시간 평균에서 벗어날 수 있다.

### 3.2 권장 계산 방식

샘플 개수가 아니라 실제 시간 폭 `step`을 사용한다.

```java
double fpsIntegral = 0.0;
double idealFpsIntegral = 0.0;
double loadObservedSec = 0.0;

if (!recovering && step > 0.0) {
    fpsIntegral += fps * step;
    idealFpsIntegral += idealFps * step;
    loadObservedSec += step;
}
```

평균 FPS는 다음과 같이 계산한다.

```java
double meanFps = loadObservedSec > 0.0
        ? fpsIntegral / loadObservedSec
        : 0.0;
```

지속 처리량 손실은 FPS 평균을 다시 나누기보다 처리량 적분값끼리 직접 비교한다.

```java
double throughputLoss = idealFpsIntegral > 1e-9
        ? Math.max(0.0,
            (idealFpsIntegral - fpsIntegral) / idealFpsIntegral * 100.0)
        : 0.0;
```

이 계산은 다음 의미를 갖는다.

```text
실제 처리량       = 시간에 대해 적분한 실제 FPS
이상적 처리량     = 같은 부하를 무스로틀 상태로 실행했을 때의 FPS 적분값
지속 처리량 손실 = 1 - 실제 처리량 / 이상적 처리량
```

### 3.3 적용 규칙

- `step == 0`인 종료 샘플은 평균과 처리량 적분에 포함하지 않는다.
- LOAD 구간만 `meanFpsLoad`와 `throughputLossPercent`에 포함한다.
- 회복 정책이 조기 적용되면 실제 `recoveryStart` 이전 구간까지만 누적한다.
- AI 부하 프로필이 있으면 매 시점의 `level`이 반영된 이상적 FPS를 적분한다.
- 반올림은 모든 적분이 끝난 뒤 결과를 반환할 때만 수행한다.
- `fpsDropPercent`는 최악 순간 낙폭이라는 별도 의미를 유지하되, 기준 구간 평균은 가능하면 시간 가중 방식으로 통일한다.

### 3.4 회귀 테스트

- `loadSeconds`가 `dt`의 배수인 경우 기존 결과가 유지되는지 확인
- `loadSeconds=10.1`, `dt=0.5` 같은 부분 스텝 조건에서 손계산 결과와 일치하는지 확인
- `step=0` 종료 샘플을 추가하거나 제거해도 평균 FPS가 변하지 않는지 확인
- 무스로틀 최대 처리량 실행에서 손실률이 0%인지 확인
- Pi4 소프트 제한 평형 조건에서 지속 손실률이 약 20%인지 확인
- TARGET_FPS 모드에서 목표 FPS를 계속 달성하면 소프트 제한이 있어도 지속 손실률이 0%인지 확인
- AI 부하 패턴의 이상적 처리량 적분과 실제 처리량 적분이 같은 시간축을 사용하는지 확인

---

## 4. 종료 시각의 클럭 상태 처리

### 4.1 현재 문제

시뮬레이터는 마지막 시각의 상태를 기록하기 위해 `step == 0`인 반복을 한 번 수행한다. 하지만 클럭 상태 갱신에는 실제 시간 폭 `step`이 아니라 고정값 `dt`가 사용된다.

```java
clockRatio += (ratioTarget - clockRatio)
        * Math.min(1.0, dt / CLOCK_SLEW_TAU_SEC);
```

따라서 다음 문제가 발생한다.

- 시간이 흐르지 않은 종료 샘플에서 클럭이 한 번 더 회복한다.
- 마지막 부분 스텝이 0.1초여도 클럭은 0.5초만큼 변한다.
- 마지막 시계열의 클럭과 FPS가 실제보다 높아질 수 있다.
- `trtServiceSec`가 실제보다 빠르게 확정될 수 있다.

### 4.2 권장 수정

클럭 상태 변화에는 실제 시간 폭을 사용한다.

```java
if (step > 0.0) {
    clockRatio += (ratioTarget - clockRatio)
            * Math.min(1.0, step / CLOCK_SLEW_TAU_SEC);
    clockRatio = Math.max(minRatio, Math.min(1.0, clockRatio));
}
```

종료 반복의 처리 순서는 다음과 같이 명확히 한다.

1. `step = min(dt, endTime - t)`를 계산한다.
2. `step == 0`이면 현재 상태를 변경하지 않는다.
3. 현재 상태로 마지막 시계열 샘플만 기록한다.
4. TTT·TED·TRT 확인 시간과 FPS 적분을 증가시키지 않는다.
5. 반복을 종료한다.

### 4.3 주의 사항

- 거버너 상태 판정과 클럭 변화는 구분해야 한다. 종료 시각의 온도로 상태를 판정할 수는 있지만 시간이 흐르지 않았다면 클럭비는 추가 변화시키지 않는다.
- `trtServiceSec` 확인 시간은 실제 경과 시간만 누적해야 한다.
- 에너지 계산은 이미 `powerW * step`을 사용하므로 `step == 0`에서 증가하지 않아야 한다.
- 2노드 방열판 모델의 부분 적분도 동일한 `step` 시간 범위 안에서만 수행한다.

### 4.4 회귀 테스트

- `step=0` 종료 반복 전후의 `clockRatio`가 같은지 확인
- 마지막 시계열의 클럭이 직전 적분 결과와 일치하는지 확인
- 종료 반복 때문에 `trtServiceSec`가 생성되지 않는지 확인
- `recoverySeconds`가 `dt`의 배수가 아닌 경우에도 결과가 수렴하는지 확인
- `dt=0.5`, `0.2`, `0.05`에서 종료 FPS와 TRT 차이가 허용 오차 안인지 확인
- 기존 에너지 테스트가 계속 통과하는지 확인

---

## 5. 구현 순서

1. `ThermalCalibrator`에 스로틀 상태 3상태 처리 도입
2. 결측률·최장 공백·불완전 에피소드 경고 추가
3. JSON·CSV `throttled` 입력 검증 강화
4. `ThermalSimulator`의 FPS 누적값을 시간 적분 방식으로 변경
5. 클럭 갱신에 `step`을 적용하고 종료 샘플의 상태 변경 차단
6. 단위 테스트와 MCP 응답 계약 테스트 추가
7. 전체 Maven 테스트 실행

각 단계는 결과 의미가 서로 다르므로 가능하면 별도 커밋으로 나누는 것이 좋다.

---

## 6. 완료 기준

- [x] `throttled=null`이 더 이상 `false`로 해석되지 않는다. (`Sample.throttleState()` → `ThrottleState` 3상태)
- [x] 결측이 포함된 에피소드는 TTT·TED·TRT 확정값을 만들지 않는다. (`extractThrottleMetrics`)
- [x] 결측 개수, 결측률, 최장 공백과 제외된 에피소드가 경고에 표시된다. (`ThrottleDataQuality` + `throttleDataQuality` 응답 필드)
- [x] `meanFpsLoad`가 `fps × step` 적분으로 계산된다.
- [x] `throughputLossPercent`가 실제·이상적 처리량 적분값으로 계산된다.
- [x] 부분 스텝의 길이가 지표 가중치에 반영된다.
- [x] `step=0`인 종료 샘플에서 클럭·확인 타이머·누적 지표가 변하지 않는다.
- [x] 마지막 FPS와 `trtServiceSec`가 종료 반복 때문에 개선되지 않는다.
- [x] 새 경계 테스트와 기존 전체 테스트가 모두 통과한다. (318개, 실패 0)

구현 시 계획서에서 구체화한 판단 두 가지:

- **TTT의 "확인 구간"** — 보정기에는 시뮬레이터 같은 지속 확인 창이 없다. 대신 *국소* 규칙으로
  옮겼다: 최초 THROTTLED **직전이 결측이면** 확정하지 않는다. 발생 시점이 결측 구간 안일 수
  있어 "언제 걸렸나"에 답할 수 없기 때문이다. 한참 앞의 무관한 결측 하나로 TTT 전체를 버리는
  과잉 보수는 피했다.
- **최장 공백의 정의** — 결측 샘플들의 시각 차이가 아니라 **마지막 관측 지점 ~ 다음 관측 지점**
  으로 잡았다. 상태 변화가 숨어 있을 수 있는 구간이 그 전체다.

권장 검증 명령은 다음과 같다.

```powershell
cd C:\Dev\MCP\waste-sim-spring
C:\Dev\tools\apache-maven-3.9.14\bin\mvn.cmd clean test
```
