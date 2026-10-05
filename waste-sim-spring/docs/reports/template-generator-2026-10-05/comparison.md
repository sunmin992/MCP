# 템플릿 자동 생성 대조 결과

- 손으로 쓴 템플릿: `simulator/src/main/resources/ses/jangnyang-templates.json`
- 생성한 초안: `template-generator/target/generated-templates.json`

## 요약

| 항목 | 값 |
|---|---|
| 손으로 쓴 템플릿 | 16개 |
| 짝을 찾은 템플릿 | 16개 |
| 채점 칸 일치 | 87 / 96 (90.6%) |
| 손으로 쓴 값이 있는 칸만 | 46 / 54 (85.2%) |
| 생성 조건이 ALWAYS 가 아닌 템플릿 (뽑지 않음) | 6개 |
| 코드에는 있으나 노출하지 않은 후보 | 24개 |
| 템플릿으로 옮기지 못한 필드 | 2개 |

채점 칸: valueType · allowed · defaultValue · min · max · unit. 질문 문장 · SES 경로 · 노드 종류는 자유 서술이라 채점하지 않는다.

## 칸별 일치

| 칸 | 일치 | 값이 있는 칸 중 일치 |
|---|---|---|
| valueType | 15/16 | 15/16 |
| allowed | 12/16 | 2/6 |
| defaultValue | 13/16 | 9/12 |
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
| jn.occupationMix | `occupationMix` | defaultValue | [BlueCollar, Student, Housewife] | null |
| jn.routeAvailableCapacity | `routeAvailableCapacityKg` | min | null | 0 |

## 노출하지 않은 후보

코드에서는 세터가 있어 정할 수 있지만 손으로 쓴 템플릿에 없는 필드다. 어느 것을 사용자에게 물을지는 제공자가 고른다.

`leaveSigma` · `wasteSigma` · `wasteMeanKg` · `capacity` · `threshold` · `scenarioScale` · `collectionIntervalDays` · `collectionDaysOfWeek` · `dischargeTimeMode` · `dischargeWindowStartMinutes` · `dischargeWindowEndMinutes` · `skipWeekends` · `weekendCollectionTimeMinutes` · `holidays` · `routeTravelMinutes` · `serviceMinutesPerSite` · `returnDischarge` · `returnFraction` · `landlordEnabled` · `landlordInspectMinutes` · `landlordThreshold` · `initialTruckLoadKg` · `routeSequence` · `trafficComplaintWeight`

## 옮기지 못한 필드

- `wasteTypes` — 템플릿 값 종류로 옮길 수 없는 타입: List<WasteType>
- `monthlyWasteFactor` — 템플릿 값 종류로 옮길 수 없는 타입: double[]
