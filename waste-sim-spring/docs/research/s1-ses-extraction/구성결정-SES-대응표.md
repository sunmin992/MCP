# 구성 결정 ↔ SES 대응표

2026-09-11 · 문항 세트 `jangnyang-simulator-v4`(34문항) · 참조 `ref-v5` · 코드 `develop @ 8b3d67f`

시뮬레이터를 구성하려면 34개를 결정해야 한다. 그 결정 각각이 SES 트리의 **어디에 앉는지**를
코드에서 확인해 적은 표다. 이것이 없으면 트리가 서 있어도 코드와 이어지지 않는다 —
대조 전에는 34개 중 **4개**만 근거로 이어져 있었다.

## 대응의 근거 — 어디서 읽었나

`JangnyangScenarioBuilder.toConfig()`가 문항 답변을 `SimulationConfig`로 옮긴다. **선언 위치가
아니라 이 소비 지점**이 그 값이 무엇을 서술하는지 알려준다.

- 직접 대응 15개 — `c.setX(f.?Or("field", ...))` 형태
- 분기 처리 12개 — enum을 읽어 여러 설정으로 나누는 것(`collectionSchedule`·`travelTimeMode` 등)
- 계산에 쓰이지 않는 4개 — 빌더가 스스로 그렇다고 적는다(`:380-386`)
- 나머지 3개 — 명세 수준에서만 쓰인다

## 표

`—`는 SES에 넣지 않는 것이고, 사유를 함께 적었다.

| # | 문항 필드 | 코드에서의 소비 | SES 자리 |
|---:|---|---|---|
| 1 | `simulationGoal` | **계산에 쓰이지 않음**(빌더 `:381`) | — 기록용. 실험의 목적 문장이고 대상 시스템의 성질이 아니다 |
| 2 | `scenarioType` | `spec.scenarioType()` · 실행 유형 분기 | `실험` **spec 실험 유형 축** |
| 3 | `engine` | java / python 어댑터 선택 (`:495`) | — **실행 수단**이다. 프롬프트 규칙 3이 제외하라고 한 범주 |
| 4 | `numBuildings` | `setNumBuildings` | `수거지점 집합.개수` **(신설)** — multi-aspect의 복제 수 |
| 5 | `residentsPerBuilding` | `setResidentsPerBuilding` | `거주민 집합.개수` **(신설)** — 건물당 복제 수 |
| 6 | `occupationPreset` | 분기 → 직업 배정 목록 | `거주민 집합.직업구성` **(신설)** — 직업 축 자식들의 구성비 |
| 7 | `days` | `setDays` | `실험.기간` |
| 8 | `seeds` | `setSeeds` | `실험.반복횟수` |
| 9 | `wasteMeanKg` | `setWasteMeanKg` | `대상 시스템.1인배출량` — 거주민별이 아니라 전역값이다 |
| 10 | `wasteSigma` | `setWasteSigma` | `대상 시스템.배출량변동` **(신설)** — 배출량이 전역이므로 그 변동도 전역이다 |
| 11 | `leaveSigma` | `setLeaveSigma` → `sampleOffset` | `거주민.외출시각변동` **(신설)** |
| 12 | `dischargeTimeMode` | 분기 → `dischargeOffset` | `거주민` **spec 배출시각 모델 축** |
| 13 | `dischargeWindow` | `getDischargeWindowStartMinutes` + span | `포항시 배출시간대 기반.배출허용창` **(교체)** — 아래 참조 |
| 14 | `capacity` | `setCapacity` → `WasteType.single(capacity, ...)` | `폐기물 유형.용량` |
| 15 | `threshold` | `setThreshold` → `WasteType.single(..., threshold, ...)` | `폐기물 유형.임계값` |
| 16 | `collectionTime` | `setCollectionTimeMinutes` | `수거차량.수거시각` |
| 17 | `collectionTimes` | `f.rawList` → 하루 여러 슬롯 | `수거차량.수거시각` (다회 변형. 같은 결정의 목록형이다) |
| 18 | `collectionSchedule` | 분기 → `setCollectionIntervalDays` / `applyDaysOfWeek` | `수거차량.수거요일` |
| 19 | `truckType` | `setTruckType` | `수거차량` **spec 차종 축** |
| 20 | `truckCount` | `setNumTrucks` | `수거차량 집합.개수` **(신설)** |
| 21 | `routeAvailableCapacityKg` | `setRouteAvailableCapacityKg` | `수거차량.적재용량` |
| 22 | `initialTruckLoadKg` | `setInitialTruckLoadKg` | `수거차량.초기적재량` **(신설)** |
| 23 | `dispatchIntervalMinutes` | `setDispatchIntervalMinutes` | `수거차량.배차간격` **(신설)** |
| 24 | `trafficMode` | 분기 → `setTrafficEnabled` | — **결합의 on/off**다. 아래 참조 |
| 25 | `trafficProfileId` | 분기 → `setTrafficProfileId` | `교통 구역.시간대프로파일` |
| 26 | `travelTimeMode` | 분기 → `TravelTimeCalculator` | `수거 경로` **spec 이동시간 방식 축** — **CP-4가 이것으로 확정된다** |
| 27 | `routeTravelMinutes` | `hopMinutes`의 상수 항 | `구간 상수.구간이동시간` **(신설)** |
| 28 | `serviceMinutesPerSite` | 모든 지점에 더하는 정차시간 | `수거차량.지점당수거시간` |
| 29 | `intraZoneTravelMinutes` | 같은 구역 안 이동 | `교통구역 근사.구역내이동시간` **(신설)** |
| 30 | `zoneAssignmentRule` | 건물 → 구역 배정 가정 | `교통구역 근사.구역배정가정` **(신설)** |
| 31 | `routeSequence` | `RoutePlanner` 방문 순서 | `수거 경로.방문순서` |
| 32 | `defaultApproval` | **계산에 쓰이지 않음**(빌더 `:381`) | — 구성 절차의 제어 |
| 33 | `inputAndScenarioConfirmed` | **계산에 쓰이지 않음** | — 구성 절차의 제어 |
| 34 | `executionApproval` | **계산에 쓰이지 않음** | — 구성 절차의 제어 |

**앉은 것 28개 · 넣지 않은 것 6개.** 대조 전 4개에서 28개가 됐다.

> 처음에 이 줄을 "30개 · 4개"로 적었다. 표의 `—` 행을 세면 여섯이다 — `simulationGoal`·`engine`·`trafficMode`·`defaultApproval`·`inputAndScenarioConfirmed`·`executionApproval`. `engine`과 `trafficMode`를 빼먹고 셌다.

## 이 작업으로 확정·교정된 것

### CP-4가 확정됐다

CP-4는 "이동시간 방식 축이 수거 경로 아래 spec인가"를 묻고, 나는 그것을 **해석**이라고 적어
두었다(`TravelTimeMode`는 코드상 `SimulationConfig`의 전역 필드다).

26번이 그것을 해결한다. 소비 지점이 `TravelTimeCalculator`이고, 그 계산의 대상은 **지점 사이의
이동**이다 — 차량의 성질(`TruckType`의 용량·기동성)이 아니고 시스템 전역의 성질도 아니다.
**경로 아래 spec이 맞다.** `ref-v5`의 CP-4 근거문에 이 소비 지점을 적었다.

### `시간대별비율`은 없는 속성이었다 — `배출허용창`으로 교체

`ref-v4`는 `포항시 배출시간대 기반.attrs = ["시간대별비율"]`이었다. 코드는 그런 것을 쓰지 않는다.

```java
// SimulationEngine:705-709
if (cfg.resolveDischargeTimeMode() == DischargeTimeMode.POHANG_ACTUAL) {
    int span = Math.max(1, cfg.dischargeWindowSpanMinutes());
    return cfg.getDischargeWindowStartMinutes() + rng.nextInt(span);
}
```

`rng.nextInt(span)` — **창 안에서 균등**이다. 시간대별 비율이 있으면 이 코드가 아니어야 한다.
`DischargeTimeMode.POHANG_ACTUAL` javadoc도 "창 안의 분포는 균등이다. 공식 데이터가 주는 것은
허용 창뿐"이라고 적는다. 그래서 이 모드가 갖는 것은 **배출허용창** 하나다.

### multi-aspect의 개수에 자리가 없었다

`수거지점 집합`·`거주민 집합`·`수거차량 집합`은 `multi` 분해만 갖고 속성이 없었다. 그런데
`numBuildings`·`residentsPerBuilding`·`truckCount`는 **그 복제 수를 정하는 결정**이다.
multi-aspect는 정의상 "개수를 정해 같은 유형을 복제한다"이므로 개수가 그 자리에 있어야 한다.
세 집합에 `개수`를 신설했다.

### `trafficMode`는 SES에 자리가 없다 — 그대로 기록한다

`APPLY`/`NONE`은 **교통 구역 → 수거 경로 결합과 교통 구역 → 교통혼잡 판정 결합을 켜고 끈다.**
SES는 엔티티·속성·분해·결합을 표현하지만 **결합의 on/off를 담는 슬롯이 없다.** 억지로
어딘가의 속성으로 넣으면 그것이 구조를 바꾼다는 사실이 사라진다.

SES pruning(가지치기)에서 결합을 포함/배제하는 선택으로 다루는 것이 형식론상 맞다. 지금
정답지 스키마에는 그 표현이 없으므로 **표에 `—`로 두고 사유를 남긴다.** 스키마를 늘릴지는
따로 판단할 일이다.

## 남은 판단 둘

**17번 `collectionTimes`를 `수거시각`과 같은 자리로 두었다.** 하루 한 번과 여러 번은 같은
결정의 단일형·목록형이고, 코드도 단일 시각을 기본으로 두고 목록이 있으면 그것을 쓴다. 다만
SES에서 "값이 하나인가 목록인가"를 구별해 적고 싶다면 별 자리가 되어야 한다.

**6번 `occupationPreset`을 `거주민 집합.직업구성`으로 두었다.** 프리셋은 직업 축 자식들의
**구성비**를 정한다 — 축에서 하나를 고르는 spec 선택이 아니다. 그래서 축이 아니라 집합의
속성으로 두었다. SES에서 multi-aspect 구성원의 유형 분포를 어떻게 적는지는 형식론 쪽 논의가
필요한 부분이다.
