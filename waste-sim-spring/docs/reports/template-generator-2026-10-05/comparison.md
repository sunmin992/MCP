# 템플릿 자동 생성 대조 결과

- 손으로 쓴 템플릿: `simulator/src/main/resources/ses/jangnyang-templates.json`
- 생성한 초안: `template-generator/target/generated-templates.json`

## 요약

| 항목 | 값 |
|---|---|
| 손으로 쓴 템플릿 | 16개 |
| 짝을 찾은 템플릿 | 16개 |
| 채점 칸 일치 | 88 / 96 (91.7%) |
| 손으로 쓴 값이 있는 칸만 | 47 / 54 (87.0%) |
| 생성 조건 같음 | 12 / 16 (75.0%) |
| 코드에는 있으나 노출하지 않은 후보 | 24개 |
| 템플릿으로 옮기지 못한 필드 | 2개 |

채점 칸: valueType · allowed · defaultValue · min · max · unit. 질문 문장 · SES 경로 · 노드 종류는 자유 서술이라 채점하지 않는다. 생성 조건은 칸 점수에 넣지 않고 아래에서 따로 센다.

## 칸별 일치

| 칸 | 일치 | 값이 있는 칸 중 일치 |
|---|---|---|
| valueType | 15/16 | 15/16 |
| allowed | 12/16 | 2/6 |
| defaultValue | 14/16 | 10/12 |
| min | 15/16 | 9/9 |
| max | 16/16 | 6/6 |
| unit | 16/16 | 5/5 |

## 템플릿별 어긋난 칸

| 템플릿 | 설정 필드 | 칸 | 손으로 쓴 값 | 생성한 값 |
|---|---|---|---|---|
| jn.trafficMode | `trafficEnabled` | valueType | ENUM | BOOLEAN |
| jn.trafficMode | `trafficEnabled` | allowed | [APPLY, IGNORE] | [] |
| jn.trafficMode | `trafficEnabled` | defaultValue | IGNORE | false |
| jn.trafficProfile | `trafficProfileId` | allowed | [jangryang-weekday, jangryang-volume-weekday] | [] |
| jn.trafficProfile | `trafficProfileId` | defaultValue | jangryang-weekday | null |
| jn.travelTimeMode | `travelTimeMode` | allowed | [LEGACY_CONSTANT, ZONE_PROXY_HYBRID] | [LEGACY_CONSTANT, OSRM_HYBRID, ZONE_PROXY_HYBRID] |
| jn.zoneAssignmentRule | `zoneAssignmentRule` | allowed | [CONTIGUOUS, ROUND_ROBIN] | [NONE, CONTIGUOUS, ROUND_ROBIN] |
| jn.routeAvailableCapacity | `routeAvailableCapacityKg` | min | null | 0 |

## 생성 조건

손으로 쓴 조건 이름을 조건 사전으로 식으로 바꾸고, 엔진에서 뽑은 식과 진리표로 맞댄다. `?{…}` 는 설정만으로 정해지지 않는 실행 조건이며 "참일 수 있다" 로 읽는다.

| 관계 | 개수 | 뜻 |
|---|---|---|
| 같음 | 12 | 손으로 쓴 조건과 같다 |
| 더 넓음 | 4 | 물어야 할 때는 빠짐없이 묻지만, 안 물어도 될 때도 묻는다 |
| 더 좁음 | 0 | 물어야 할 때 빠뜨릴 수 있다 |
| 다름 | 0 | 어느 쪽도 포함하지 않는다 |

| 템플릿 | 손으로 쓴 조건 | 손으로 쓴 식 | 뽑은 식 | 관계 |
|---|---|---|---|---|
| jn.truckType | ALWAYS | `true` | `true` | 같음 |
| jn.truckCount | ALWAYS | `true` | `!(numBuildings < 1 \|\| numBuildings > 26)` | 같음 |
| jn.dispatchInterval | EFFECTIVE_TRUCKS_AT_LEAST_2 | `numTrucks >= 2 && numBuildings >= 2` | `?{반복 변수 k ≥ 1 일 때만 값이 쓰인다 — 반복이 두 번 이상 돌아야 함 (k < routes.size())} && !?{route.isEmpty()} && ?{isTruckDay(d, cfg, types)}` | 더 넓음 |
| jn.collectionTime | SINGLE_COLLECTION_TIME | `!given(collectionTimesMinutes)` | `?{isTruckDay(d, cfg, types)} && !(?{isWeekend(day)} && present(weekendCollectionTimeMinutes)) && !(present(collectionTimesMinutes) && !empty(collectionTimesMinutes))` | 같음 |
| jn.collectionTimes | MULTI_COLLECTION_TIMES_GIVEN | `given(collectionTimesMinutes)` | `(?{isTruckDay(d, cfg, types)} && !(?{isWeekend(day)} && present(weekendCollectionTimeMinutes))) \|\| (?{isTruckDay(d, cfg, types)} && !(?{isWeekend(day)} && present(weekendCollectionTimeMinutes)) && ?{자기 자신: present(c…` | 더 넓음 |
| jn.trafficMode | ALWAYS | `true` | `true` | 같음 |
| jn.trafficProfile | TRAFFIC_APPLIED | `trafficEnabled == true` | `trafficEnabled == true` | 같음 |
| jn.travelTimeMode | ALWAYS | `true` | `true` | 같음 |
| jn.zoneAssignmentRule | ZONE_PROXY_OVER_4_BUILDINGS | `travelTimeMode == ZONE_PROXY_HYBRID && numBuildings > 4` | `(?{pos > 0} && !?{route.isEmpty()} && ?{isTruckDay(d, cfg, types)} && travelTimeMode == ZONE_PROXY_HYBRID && !(travelTimeMode == LEGACY_CONSTANT)) \|\| travelTimeMode == ZONE_PROXY_HYBRID \|\| (trafficEnabled == true …` | 더 넓음 |
| jn.intraZoneTravel | CONTIGUOUS_ZONE_RULE | `travelTimeMode == ZONE_PROXY_HYBRID && numBuildings > 4 && zoneAssignmentRule == CONTIGUOUS` | `(?{pos > 0} && !?{route.isEmpty()} && ?{isTruckDay(d, cfg, types)} && travelTimeMode == ZONE_PROXY_HYBRID && !(travelTimeMode == LEGACY_CONSTANT) && ?{fromZone.equals(toZone)}) \|\| (?{pos > 0} && !?{route.isEmpty()} …` | 더 넓음 |
| jn.numBuildings | ALWAYS | `true` | `true` | 같음 |
| jn.residentsPerBuilding | ALWAYS | `true` | `true` | 같음 |
| jn.occupationMix | ALWAYS | `true` | `true` | 같음 |
| jn.days | ALWAYS | `true` | `true` | 같음 |
| jn.seeds | ALWAYS | `true` | `true` | 같음 |
| jn.routeAvailableCapacity | ALWAYS | `true` | `true` | 같음 |

### 같지 않은 것

- **jn.dispatchInterval** (더 넓음) — 반례 `{numTrucks=1.0, numBuildings=1.0} → 손 false · 생성 true`
  - 실행 조건: `반복 변수 k ≥ 1 일 때만 값이 쓰인다 — 반복이 두 번 이상 돌아야 함 (k < routes.size())`
  - 실행 조건: `route.isEmpty()`
  - 실행 조건: `isTruckDay(d, cfg, types)`
- **jn.collectionTimes** (더 넓음) — 반례 `{collectionTimesMinutes=NULL, weekendCollectionTimeMinutes=0.0} → 손 false · 생성 true`
  - 실행 조건: `isTruckDay(d, cfg, types)`
  - 실행 조건: `isWeekend(day)`
- **jn.zoneAssignmentRule** (더 넓음) — 반례 `{travelTimeMode=LEGACY_CONSTANT, numBuildings=1.0, trafficEnabled=true} → 손 false · 생성 true`
  - 실행 조건: `pos > 0`
  - 실행 조건: `route.isEmpty()`
  - 실행 조건: `isTruckDay(d, cfg, types)`
- **jn.intraZoneTravel** (더 넓음) — 반례 `{travelTimeMode=ZONE_PROXY_HYBRID, numBuildings=1.0, zoneAssignmentRule=NONE} → 손 false · 생성 true`
  - 실행 조건: `pos > 0`
  - 실행 조건: `route.isEmpty()`
  - 실행 조건: `isTruckDay(d, cfg, types)`
  - 실행 조건: `fromZone.equals(toZone)`

## 노출하지 않은 후보

코드에서는 세터가 있어 정할 수 있지만 손으로 쓴 템플릿에 없는 필드다. 어느 것을 사용자에게 물을지는 제공자가 고른다.

`leaveSigma` · `wasteSigma` · `wasteMeanKg` · `capacity` · `threshold` · `scenarioScale` · `collectionIntervalDays` · `collectionDaysOfWeek` · `dischargeTimeMode` · `dischargeWindowStartMinutes` · `dischargeWindowEndMinutes` · `skipWeekends` · `weekendCollectionTimeMinutes` · `holidays` · `routeTravelMinutes` · `serviceMinutesPerSite` · `returnDischarge` · `returnFraction` · `landlordEnabled` · `landlordInspectMinutes` · `landlordThreshold` · `initialTruckLoadKg` · `routeSequence` · `trafficComplaintWeight`

## 옮기지 못한 필드

- `wasteTypes` — 템플릿 값 종류로 옮길 수 없는 타입: List<WasteType>
- `monthlyWasteFactor` — 템플릿 값 종류로 옮길 수 없는 타입: double[]
