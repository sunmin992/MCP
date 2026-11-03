# 지역명으로 시나리오 구성하기

"덕명동 시뮬레이터 실행해줘", "연남동 쓰레기 수거 돌려줘"처럼 **어느 동네 이름이든** 같은 생활폐기물 수거 모델로 처리한다.
지역마다 새 시뮬레이터 서버, Java 클래스, 카탈로그 항목을 만들지 않는다.
시뮬레이터가 공개 지도(OpenStreetMap)에서 그 지역의 실제 주거 블록을 찾아 구성하고, 못 찾으면 공통 기본값의 가상 블록으로 구성한다.

## 동작 흐름

1. MCP 클라이언트 LLM이 사용자 발화에서 도메인과 지역명을 추출한다.
2. 브로커 find_simulators에 requirements.regionId를 전달한다. 등록되지 않은 지역이어도
   PUBLIC_MAP_BLOCK_FOR_ANY_LABEL 정책을 가진 제공자는 조건부 후보(CONDITIONAL)가 된다.
3. 선택된 제공자의 create_neighborhood_scenario에 요청 원문·regionName·selection을 전달한다.
4. 제공자는 지역을 다음 순서로 찾는다.
   1. 제공자가 등록한 지역(장량동 모델 `jangnyang`, 합성 블록 등)
   2. 이전에 수집해 캐시한 공개 지도 블록
   3. 공개 지도에서 새로 수집 — 아래 "공개 지도 블록" 참조
   4. 모두 실패하면 공통 기본값의 가상 블록(ASSUMED_BLOCK)과 실패 사유(fallbackReason)
5. 같은 이름의 지역이 여러 곳이면 고르지 않는다. `state=NEEDS_REGION`과 `regionCandidates`를 반환하므로
   사용자에게 어느 곳인지 묻고, 고른 후보의 regionName(예: "중앙동 중구 부산광역시")으로 다시 호출한다.
6. 사용자 지정 값과 필수 조건을 먼저 적용하고, 남은 필수 결정은 지역 자료 → 템플릿 기본값 순으로 채운다.
7. Task·설정·목적·지역 제약 검증을 거쳐 UNCONFIRMED로 저장하고 confirmUrl을 반환한다.
   확인 화면에는 지역 자료의 출처 등급표가 함께 나온다.
8. 사용자가 승인하면 get_scenario_status로 토큰을 조회하고 run_scenario_by_token으로 실행한다.

시나리오 생성 도구는 실행하거나 승인하지 않는다.

## 공개 지도 블록

| 단계 | 하는 일 | 자료 |
|---|---|---|
| 지역 확정 | 지역명 → 행정 경계 또는 동 중심점. 같은 동의 경계·지점 중복은 이름·시군구·시도로 묶는다 | Nominatim |
| 건물 수집 | 경계 안(경계가 없으면 중심 반경 700m) 건물의 용도·층수·외곽선 | Overpass |
| 블록 선택 | 반경 150m 안 주거 건물이 가장 많은 건물을 중심으로 300m 안 가까운 건물 최대 26동 | 계산 |
| 방문 순서 | 중심 건물에서 최근접 순회. 엔진의 Node_A, Node_B … 순서 | 계산 |
| 도로 이동 | 방문 순서대로 차량 경로를 조회해 구간 평균 이동시간 | OSRM |

엔진은 건물 좌표를 받지 않으므로 블록을 세 입력값으로 줄인다. 모든 값에 출처 등급이 붙는다.

| 입력 | 값 | 등급 |
|---|---|---|
| 건물 수 `numBuildings` | 블록 안 주거 건물 수 | OBSERVED |
| 동당 세대 수 `residentsPerBuilding` | 바닥면적 × 층수 ÷ 유형별 세대당 면적의 블록 평균. 층수 태그가 없으면 유형별 기본 층수 | ESTIMATED |
| 구간 이동시간 `routeTravelMinutes` | 도로 이동(OSRM) + 정차·상차 가정 12분 | 도로: OBSERVED, 정차: ASSUMED |
| 배출 시간대 | 자동 수집하지 않음. 공통 기본값 | ASSUMED |
| 건물 용도 | OSM building 태그. 주거 용도가 4동 미만이면 용도 미상(building=yes)을 주거로 가정 | OBSERVED 또는 ASSUMED |

- 정차·상차 12분은 장량동 기준선의 구간 상수 15분에서 장량동 OSRM 구간 약 3분을 뺀 값이다.
- 세대당 면적: 아파트 85㎡(기본 15층), 다세대·주거 60㎡(기본 4층), 기숙사 25㎡(기본 5층), 주택 60㎡(기본 2층).
- 도로 경로 조회에 실패하면 직선거리 × 1.4를 시속 20km로 나눈 값을 쓰고 ESTIMATED로 표시한다.
- 자료 등급: 도로 이동이 실측이고 용도 가정이 없으면 REAL_BLOCK, 아니면 PARTIAL_BLOCK.

수집한 블록과 조회 원문(Nominatim·Overpass·OSRM 응답)은 `region.cache-dir`(기본 `data/regions`)에 저장한다.
같은 지역을 다시 요청하면 네트워크를 쓰지 않고 캐시를 쓴다. 블록 파일이 바뀌면 지역 식별값이 바뀌어 기존 검증·승인이 무효화된다.
지역 자료를 새로 받으려면 해당 캐시 파일을 지운다.

## 설정

`simulator/src/main/resources/application.properties`:

| 속성 | 환경 변수 | 기본값 |
|---|---|---|
| region.resolver.enabled | REGION_RESOLVER_ENABLED | true. false면 미등록 지역은 항상 가상 블록 |
| region.cache-dir | REGION_CACHE_DIR | data/regions |
| region.nominatim-url | NOMINATIM_URL | https://nominatim.openstreetmap.org |
| region.overpass-url | OVERPASS_URL | https://overpass-api.de/api |
| region.osrm-url | REGION_OSRM_URL | https://router.project-osrm.org |
| region.user-agent | REGION_USER_AGENT | 연구용 식별 문자열 |

공개 서버 이용 정책에 따라 호출 간 1초 이상을 두고, 429·5xx 응답은 간격을 늘려 세 번까지 다시 시도한다.
처음 보는 지역은 수집에 30~50초가 걸린다. 테스트(pom)에서는 수집기를 끄고 녹화한 응답만 쓴다.
자료 출처는 © OpenStreetMap 기여자이며 ODbL을 따른다.

## 호출 예

```json
{"request":"덕명동 시뮬레이터 실행해줘","regionName":"덕명동","values":{"days":2,"seeds":1}}
```

같은 이름이 여러 곳인 경우의 응답:

```json
{"valid":false,"state":"NEEDS_REGION","message":"같은 이름의 지역이 15곳입니다. 시·구를 함께 알려 주십시오",
 "regionCandidates":[{"regionName":"중앙동 중구 부산광역시","displayName":"중앙동, 중구, 부산광역시, 대한민국"}]}
```

지역 미제시 시 "가상 동네"의 가상 블록이 된다. 특정 입력만 바꾸려면 values에 템플릿 답변키를 넣는다.
사용자가 지정한 값(예: numBuildings)은 지역 자료보다 우선한다.

## 한계

- 동 전체가 아니라 동 안의 실제 주거 블록 하나(최대 26동)다. 동 전체 예측을 주장하지 않는다.
- 세대 수는 건물 형태로 추정한 값이다. 실제 세대 수(건축물대장)나 인구 통계와 대조하지 않았다.
- OSM 자료의 충실도는 지역마다 다르다. 농촌은 주거 건물이 지도에 거의 없어 가상 블록으로 내려갈 수 있다.
- 블록 안 도로 이동은 대개 1분 미만이라, 분 단위 구간 상수에서는 정차 가정 12분이 대부분을 차지한다.
- 배출 시간대·교통 혼잡·수거 정책은 지역 자료가 아니다. 장량동 교통·포항 배출 조건은 다른 지역에 적용하지 않는다.
- 결과는 실제 블록 배치에 기반한 가정적 수거 운영 비교이며, 실측 민원과 비교한 모델 타당성 검증이 아니다.

실험 결과: [지역명 자동 구성 실험](../reports/region-resolution-2026-11-03/REPORT.md).
