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
