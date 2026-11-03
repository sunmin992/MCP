# P0 기준선 확인 결과

기준 커밋: 0ddc071. 실행일: 2026-11-03 (Asia/Seoul).
Java 21.0.10, 저장소 Maven Wrapper 사용. 제품 소스와 테스트 기대값은 변경하지 않았다.

## 테스트 결과

| 대상 | 전체 | 통과 | 실패 | 오류 | 스킵 |
|---|---:|---:|---:|---:|---:|
| mcp-common | 0 | 0 | 0 | 0 | 0 |
| broker | 148 | 148 | 0 | 0 | 0 |
| simulator | 687 | 684 | 0 | 0 | 3 |
| template-generator | 45 | 44 | 1 | 0 | 0 |
| parking-sim | 89 | 89 | 0 | 0 | 0 |
| counter-sim | 31 | 31 | 0 | 0 | 0 |
| district-waste-sim | 40 | 40 | 0 | 0 | 0 |
| 합계 | 1040 | 1036 | 1 | 0 | 3 |

공통 모듈은 자체 테스트 보고서가 없으며 제공자 테스트에서 공통 규칙을 사용한다.
스킵 3건은 PythonWasteSimAdapterTest의 외부 Python 참조 엔진 부재에 따른 조건부 중단이다.

실행 순서:

1. 루트 `.\mvnw.cmd -B clean test`: 샌드박스에서 브로커 테스트 중단. 임시 파일의 원자적 이동이 AccessDeniedException으로 실패했고 Spring 컨텍스트 초기화 관련 오류도 발생했다. 이 실행은 코드 기준선 성공 여부에 사용하지 않았다.
2. 정상 권한에서 루트 `.\mvnw.cmd -B -fn clean install`: 모든 모듈의 테스트를 계속 실행하고, 독립 제공자에 필요한 공통 모듈을 설치했다. `-fn`은 실패를 무시하므로 종료 코드 0을 전체 통과로 해석하지 않았다. Surefire XML과 모듈 로그로 판정했다.
3. 정상 권한에서 parking-sim, counter-sim, district-waste-sim 각각 `.\mvnw.cmd -B clean test`: 모두 BUILD SUCCESS.
4. `python docs/reports/p0-baseline-2026-11-03/collect_baseline.py`: 계약 원문과 Surefire 집계 저장.

로그는 같은 폴더의 root-test.log, root-test-unrestricted.log와 각 독립 프로젝트의 *-test.log에 있다.
test-summary.json은 정상 권한 실행 후 생성된 Surefire XML 집계다. collect_baseline.py는 현재 target 보고서를 읽으므로 나중에 다시 실행하면 최신 결과로 바뀐다.

## 기존 실패 재현과 처리

P0-001: `JangnyangSourceTest.뽑은_생성_조건은_물어야_할_때를_빠뜨리지_않는다` 실패.

- 위치: template-generator/src/test/java/com/wastesim/templategen/JangnyangSourceTest.java:102.
- binCapacity(capacity)와 complaintThreshold(threshold)의 수기 조건은 ALWAYS인데 추출 조건은 `!present(wasteTypes) || empty(wasteTypes)`로 더 좁다. 예상 0건, 실제 2건.
- 기존 scenario-readiness.md에 기록된 실패와 동일하다. P0에서 제품 코드 변경 없이 재현했다.
- 일반 브로커/시나리오 실행 테스트를 막지는 않지만 전체 테스트 green 및 템플릿 생성기 정확도 주장을 막는다.
- 후속 작업: wasteTypes 분기에서 공통 capacity/threshold와 종류별 대체값의 실제 사용 관계를 확인하고, 추출기 조건 또는 제공자 템플릿 중 실제 의미와 어긋난 쪽을 수정한다. 테스트 기대값 완화로 숨기지 않는다. P1의 계약 작업과 별도로 추적하며 전체 green을 요구하는 병합 전 해결한다.

## 계약 조사와 결정

상세 비교와 버전 정책: [요구·능력 계약](../../specifications/request-capability-contract.md).
네 제공자의 카드·템플릿·Task 명세 원문: [contract-snapshot.json](contract-snapshot.json).

확인한 핵심 차이:

- 요청에는 지역 ID·기간·시간 해상도·필수/선호 조건의 독립 필드가 없다.
- 네 능력 카드에 schemaVersion이 없다. SES·Task·애플리케이션 버전과 혼용하지 않아야 한다.
- 장량동의 내부 ToolResult는 needsConfirm/warnings가 추가되어 있다. MCP 출력이 내부 봉투 전체를 그대로 전달한다고 가정하면 안 된다.
- 오류는 내부 code/field/message, MCP text/isError, 등록 HTTP 응답으로 경계별 형식이 다르다.
- 목적 및 근거 검사는 이미 있지만 생략은 NOT_SPECIFIED/NOT_CHECKED이며 검증 성공이 아니다.
- 복수 후보와 점수 없는 비교가 이미 있으므로 새 점수 엔진을 추가하지 않는다.

P1 정책은 문서로 확정했으며 런타임 구현은 아직 없다. 기존 입력을 legacy로 수용하고, 새 조건의 명세 누락은 정보 부족으로 처리하며, 알려지지 않은 명시적 major 버전은 거절하도록 계획한다.

## P0 완료 범위와 다음 작업

기준선 실행, 기존 실패 재현, 네 제공자 계약 비교, 버전/호환 정책, 변경 대상 목록 작성을 완료했다.
실행 중 서버에 대한 MCP 통합 호출이나 UI 시각 검증은 수행하지 않았다. 해당 작업은 P4/P5 범위다.
다음 작업은 P1의 공통 요청·능력 계약과 항목별 판정 구현이며, P0-001은 별도 수정 대상으로 남긴다.
