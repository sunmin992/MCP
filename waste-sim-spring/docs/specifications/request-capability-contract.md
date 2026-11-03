# 요구·능력 계약 기준과 변경 정책

2026-11-03 P0. 기준 코드 0ddc071. 아래의 현행 계약과 P1 도입 정책을 구분한다.
P0 당시에는 정책만 정리했다. 후속 구현은 [구현 결과](../reports/implementation-2026-11-03/REPORT.md)를 따른다.

## 현행 요청과 책임

| 경계 | 현행 계약 | 한계 / 후속 변경 |
|---|---|---|
| 자연어 → 브로커 | RequestProfile: domain 필수, spatialScale/environmentConditions/objective/comparisonAxes/population 선택. find_simulators에는 matchId/targetServerId도 있음 | 지역 ID·기간·시간 해상도·필수/선호 조건이 독립 필드로 없음 |
| 브로커 → 후보 | 카드의 domainAliases, analysisUnit.matchKeys, unsupported 등 제공자 어휘로 비교 | 일반화된 수치/단위/시간 계약 없음. region 문자열은 지원 지역 판정 계약이 아님 |
| 후보 → 사용자 | MATCH/ADJUST_REQUEST/CHOOSE/NONE, 후보별 reasons/mismatches/ambiguities/requestAdjustments | 조건별 공통 구조와 사유 코드 필요. 복수 일치는 자동 순위 없이 사용자 선택 |
| 제공자 → 브로커 | POST /api/simulators: 카드 등록. serverId/endpoint, 파일 후보 ID 충돌, populationTypes key 중복 등 검사 | 카드 전체의 버전 및 형식 검증은 아직 없음. 등록 채널 분리는 인증 보장을 의미하지 않음 |
| LLM → 제공자 | build_scenario: values/variableAnswerKey/variableValues/request 필수. origins/observations/requestId/purpose/userUtterances/evidence 선택 | purpose 생략은 NOT_SPECIFIED. 발화 생략은 NOT_CHECKED. 자연어 전체 의미 검증이 아님 |
| 제공자 → 실행 | Task 준비 검사, 설정 검증·역검증, 검증 기록, 사람 확인 후 토큰 | ready/valid/사용자 승인은 각각 다른 상태이며 합쳐 해석하지 않음 |

미제시 null과 명시적 빈 목록은 기존 RequestProfile에서 다르게 취급한다.
LLM은 자연어를 구조화하며, 브로커는 자연어 해석 모델을 직접 호출하지 않는다.

## 네 제공자 계약 비교

| 항목 | 장량동 | 주차 | 민원창구 | 구 단위 폐기물 |
|---|---|---|---|---|
| serverId | jangnyang-waste-sim | pohang-parking-sim | pohang-counter-sim | bukgu-district-waste-sim |
| 범위 | 원룸촌 한 블록 | 도심 한 구역 주차장 | 센터 한 곳 | 행정동 구역 집계 |
| 카드 공통 부분 | serverId/name/endpoint/domain/domainAliases/region/analysisUnit/populationTypes/sesDigest/applicability/quality/cost/performance/conditional/unsupported/neverDo/alwaysAttachToResult | 동일 | 동일 | 동일 |
| 카드 추가 부분 | sourceSha | fictional/resultFields | fictional/resultFields | fictional/resultFields |
| 카드 schemaVersion | 없음 | 없음 | 없음 | 없음 |
| 템플릿 루트 | sesId/sesVersion/templates | 동일 | 동일 | 동일 |
| Task 명세 루트 | version/tasks/observations/resources | 동일 | 동일 | 동일 |
| 구성 요청 차이 | planId로 계획 이력 연결 | parkingSnapshotId, initialVehicleRemainingMinutes 추가 | 공통 구성 필드 | 공통 구성 필드 |
| 내부 ToolResult | ready/result/errors/needsConfirm/warnings | ready/result/errors | ready/result/errors | ready/result/errors |

카드의 개별 도메인 정보와 지표 목록은 reports/p0-baseline-2026-11-03/contract-snapshot.json에 원문으로 보관한다.
템플릿·Task 명세의 루트 구조가 같아도 결정키·검증 함수·단위까지 동일하다는 뜻은 아니다.

## 오류와 상태 계약

- 내부 ValidationError의 공통 형식은 code/field/message다. 오류 enum은 도메인별로 다르므로 도메인 전용 코드를 공통 오류로 가정하지 않는다.
- MCP 도구 결과는 content의 text와 isError를 사용한다. 성공 시 result를 직렬화하며 내부 ToolResult의 모든 필드를 그대로 보내는 계약이 아니다.
- 검증 실패는 현재 '검증 실패: '와 직렬화한 errors를 포함한 text로 반환한다. 구조화 오류 봉투로의 변경은 명시적인 계약 변경이다.
- JSON-RPC 미지원 method는 -32601, 처리 예외는 -32603이다. 도구 검증 실패와 전송/프로토콜 오류를 구분한다.
- 등록 실패는 HTTP 400과 registered=false/reason이며 도구 오류 형식과 다르다.
- 적합 후보 없음은 정상 도메인 결과 NONE이다. 실행 오류로 변환하지 않는다.

## P1 버전·호환 정책

1. 앱 버전, MCP initialize serverInfo.version, 카드 계약 버전, SES 버전, Task 명세 버전을 별도 관리한다. 현행 버전 숫자를 카드 스키마 버전으로 간주하지 않는다.
2. 새 공통 요청·카드에는 schemaVersion을 도입한다. 현행 버전 없는 입력은 legacy로 구분해 기존 경로를 유지하되 새 조건 검증을 완료했다고 표시하지 않는다.
3. 선택 필드 추가는 하위 호환 변경으로 취급한다. 필수 필드 추가·기존 필드 의미/단위 변경·기존 판정 의미 변경은 새 major 계약으로 처리한다.
4. 지원하지 않는 명시적 major 버전은 등록 또는 요청 단계에서 사유와 함께 거절한다. 버전 없는 기존 카드와 알 수 없는 새 버전을 혼동하지 않는다.
5. 요청에 새 필수 조건이 있는데 카드에 대응 능력이 없으면 정보 부족으로 판정한다. 조건이 없다는 이유로 미지원이라고 단정하지 않는다.
6. null/빈 목록/명시값의 차이를 유지한다. 숫자 0을 누락으로 취급하지 않는다. 값과 단위를 함께 검증한다.
7. 기존 reasons/mismatches/ambiguities/requestAdjustments와 기존 verdict 의미를 유지하면서 조건별 판정 배열을 추가한다.
8. 조건부 충족은 가능한 변환·추가 검증을 설명할 뿐 실행 승인이 아니다. 조건 해소 전 적합 확정 및 실행을 차단한다.
9. 선호 조건은 필수 조건 실패를 상쇄하지 않는다. 복수 적합 후보는 기존 CHOOSE 경로를 유지한다.
10. 오류 형식은 P1에서 공통 사유 코드를 추가하되 기존 MCP text 표현을 유지한다. 제거 또는 전면 교체 시 클라이언트 마이그레이션과 새 major 버전이 필요하다.

## 후속 변경 대상

- mcp-common: 버전 있는 요구/능력/항목별 판정 계약과 단위 표현.
- broker: RequestProfile, FindSimulatorsTool 스키마, CandidateMatcher, MatchResult, CandidateRegistry 등록 검사.
- 네 제공자: 능력 카드·로더, 지원 시간/지역/출력 선언, 이전 카드 호환 검사.
- 통합 경계: matchId → requestId 연결 및 선택 당시 요구 전달은 P4에서 수행.
- 지역 데이터 공급 구조와 지역 산출물 Task 검사는 P2/P3에서 수행. P1에서 선언만 추가했다고 지역 일반화를 완료한 것으로 표시하지 않는다.
