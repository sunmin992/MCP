# 테스트 결과 (2026-11-03)

`./mvnw -B test` (루트, 실행 중인 서버가 jar 를 잡고 있어 clean 없이 실행). Surefire XML 합산.

| 모듈 | 전체 | 실패 | 오류 | 스킵 |
|---|---:|---:|---:|---:|
| broker | 156 | 0 | 0 | 0 |
| simulator | 710 | 0 | 0 | 3 |
| template-generator | 45 | 0 | 0 | 0 |
| 합계 | 911 | 0 | 0 | 3 |

- 새 테스트: `PublicMapRegionTest` 7건(녹화 응답, 네트워크 없음).
- 스킵 3건은 기존 Python 참조 엔진 부재에 따른 조건부 중단이다.
- 독립 제공자(parking-sim, counter-sim, district-waste-sim)는 이번 변경 대상이 아니어서 다시 돌리지 않았다.
  공통 계약에는 지역 정책 값 하나만 추가했다.
- `RegionResolutionExperiment`는 실제 공개 서버를 쓰므로 기본 테스트에서 빠지며 따로 실행했다(REPORT.md).

## 실제 서버 시연

새 빌드로 브로커(8089)와 시뮬레이터(8090)를 다시 띄우고 MCP 도구로 호출했다. 사용자 승인은 하지 않았다.

| 호출 | 결과 |
|---|---|
| find_simulators(regionId=망원동, 2일, 민원 수) | MATCH, 지역 조건 CONDITIONAL, create_neighborhood_scenario 안내 |
| create_neighborhood_scenario(망원동) | 실험에 없던 지역. 지도에서 새로 수집, 서울 마포구 망원1동 주거 블록 26동, 동당 9세대(추정), 구간 12분, UNCONFIRMED |
| create_neighborhood_scenario(중앙동) | NEEDS_REGION, 후보 15곳, 시나리오 미생성 |
| create_neighborhood_scenario(망원동) 재호출 | 캐시 사용. 건물 수·세대 수 출처가 REGION_DATA로 바뀌어 미승인 기본값 목록에서 빠짐 |
| create_neighborhood_scenario(덕명동) | 대전 유성구 주거 블록 9동, 동당 111세대(추정), 구간 13분, UNCONFIRMED |

확인 화면(confirm.html)에서 지역 라벨, OSM 위치, 자료 등급, 출처 등급표가 표시되는 것을 확인했다.

## 사용자 승인 후 실행 (scn-221018dd, 덕명동)

사용자가 확인 화면에서 승인한 뒤 run_scenario_by_token 으로 실행했다. 2일, 반복 1회.

| 지표 | 값 |
|---|---:|
| 민원 수 | 1,282건 (생산직 430 · 학생 663 · 전업주부 189) |
| 수거장 최대 적재 | 105.3kg / 용량 30kg (적재율 351%) |
| 잔여 폐기물 | 278.0kg |
| 차량 가동률 | 15.07% |
| 평균 운행 소요 | 104분 |

해석 주의: 동당 111세대(추정)인데 수거장 용량은 공통 기본값 30kg(약 25세대 기준)이다.
민원 수가 큰 것은 지역 차이보다 이 불일치 때문이다. 세대 수에 맞춘 수거장 용량은 아직 지역 자료로 정하지 않는다.

## 후속 수정: 수거장 용량 자동 산정

위 불일치를 고쳐, 공개 지도 블록은 수거장 용량을 동당 세대 수 × 1.2kg(장량동 기준선 30kg/25세대 비율)로 정한다(ESTIMATED, 출처 REGION_DATA).
묶음 버전을 1.1.0으로 올려 옛 캐시는 다시 수집된다. 덕명동은 수거장 용량 133kg으로 다시 구성됐다(scn-798a0282, 승인 대기).
테스트: 912건 중 실패 0건, 스킵 3건(용량 산정·옛 캐시 재수집 검사 추가).
실험 표(REPORT.md)와 bundles/ 는 용량 추가 전(1.0.0) 묶음이다.
