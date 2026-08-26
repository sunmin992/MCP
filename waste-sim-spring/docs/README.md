# 문서 인덱스

프로젝트 문서를 역할별로 분류한다. SRS·SDD·TDD의 기준은 통합 명세서 한 개이며, 나머지 Markdown 파일은 운영 가이드와 참고 기록으로만 사용한다.

## 기준 명세서

- [waste-sim-spring SRS·SDD·TDD 통합 명세서 v1.11](specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_11.md) (이전 버전: [v1.10](specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_10.md))
- [정합 상태와 대조 결과](specifications/README.md) — 명세와 코드가 어긋나는 지점을 여기서 관리한다

v1.9부터 기준 명세서를 Markdown으로 관리한다. Word 원본은 바이너리라 세 대의 개발 머신에서 병합이 불가능했고,
그래서 v1.8까지는 저장소에 두더라도 어느 문단이 언제 바뀌었는지 diff로 확인할 수 없었다.
**어긋나면 저장소의 Markdown이 이긴다** — `.docx`는 사람이 문단을 쓰기 편한 편집 원본일 뿐이고 `.gitignore` 대상이다.
변환은 [`scripts/docx_to_markdown.py`](../scripts/docx_to_markdown.py)로 고정해 두었다(절차는
[specifications/README.md](specifications/README.md) 참고).

통합 명세서에는 다음 내용이 포함되어 있다.

- SRS: 기능·비기능 요구사항, 외부 인터페이스, MCP 도구, 교통 및 엣지 요구사항
- SDD: 도메인 허브, 결정론적 채팅 게이트, DEVS 엔진, MCP 확장점, 엣지 열 모델
- TDD: 단위·통합 테스트 설계, 요구사항 추적 매트릭스와 합격 기준

## 운영 및 연동 가이드

- [환경 설정](guides/ENV_SETUP.md)
- [교통 CSV 연결](guides/CONNECT_TRAFFIC_CSV.md)
- [MCP 모델 연결 방법](guides/MCP_MODEL_INTEGRATION.md)
- [엣지 발열 실험 설계](guides/EDGE_THERMAL_EXPERIMENT.md)

## 참고 및 변경 기록

- [설계 결정 기록](reference/DESIGN_DECISIONS.md)
- [채팅 자연어 요청 카탈로그](reference/CHAT_REQUEST_CATALOG.md)
- [디버깅 점검 목록](reference/DEBUGGING_ISSUES.md)
- [팬 RPM 스윕·최적점 탐색 설계](reference/FAN_RPM_SWEEP_DESIGN.md)
- [LLM 벤치마크 해석 규칙](reference/LLM_BENCHMARK_GUIDE.md)

설계 결정 기록은 통합 명세서 v1.7 이후 코드와 함께 확정된 결정의 근거를 보존하므로 삭제하지 않는다. 디버깅 목록은 아직 명세서에 반영되지 않은 결함과 테스트 공백을 관리한다 — v1.8이 P1 4건(E-01, E-02, W-01, W-03), v1.9가 E-04와 P2 잔여분을 반영했고, v1.10이 새로 발견된 게이트 어휘 결함(D-33)을 반영했다. E-06·E-08은 2026-08-26에 결정(D-37·D-38)과 회귀 테스트로 해소되어 부록 B.2는 비었다.

## 문서 관리 규칙

- 요구사항·설계·테스트 기준은 통합 명세서에서 관리한다.
- 실행법, 데이터 연결과 실험 절차는 `guides/`에서 관리한다.
- 의사결정 근거, 자연어 예제와 결함 목록은 `reference/`에서 관리한다.
- 동일 내용을 별도 SRS/SDD/TDD Markdown으로 복제하지 않는다.
- 새 버전 명세서를 만들면 `specifications/`에 추가하고 이 인덱스의 기준 링크를 갱신한다.

## 검증 메모

v1.11을 저장소 기준 명세로 반영했다(2026-08-26). 이 개정에서 **v1.9·v1.10 Markdown이 실제로는 한 번도
커밋된 적이 없어 기준 링크가 존재하지 않는 파일을 가리키고 있었다는 사실**을 확인하고, v1.11과 v1.10을
같은 변환 스크립트로 만들어 함께 커밋했다. 함께 반영: Maven Wrapper 추가(`./mvnw -B test`가 기본 검증 명령),
부록 B.2에 남아 있던 E-06·E-08의 결정(D-37·D-38)과 회귀 테스트, 문서/애플리케이션 버전 체계 분리(`/actuator/info`).
전체 테스트 **441건 통과·2건 스킵**(Python 참조 엔진 미설치 환경) 기준이다.

v1.10은 truck-route 시나리오(FR-113, SDD 2.14.4)의 설계 근거·테스트를 전건 반영했다 — v1.9까지는 FR-07 숫자와
2.14.1 목록에만 표면적으로 언급돼 있었다. 함께 반영: EdgeToolSelector 검사 순서 결함(FR-78, 이미 v1.9 재대조에서
코드는 수정됨), DomainIntentDetector의 팬 어휘(rpm·pwm·회전수·운전점) 누락 결함(D-33, 신규 발견), 부록 B.3의
미완성 목록(E-06·E-08 잔여 회귀 테스트 항목) 완성. 마크다운 테이블 열 정합성과 pandoc 렌더링을 검증했다.

v1.9는 1633줄 Markdown이며, 2노드 열모델·팬 전력·시변 부하·팬 배열/RPM 스윕·트럭 용량 모델을 반영했다(개정 이력 13항목).
2026-08-17 재대조(`mvn -B test` 425건 통과·2건 스킵)에서 코드와 어긋난 **11곳을 전부 명세서에 반영했다** — 시나리오 12종(truck-route 신설),
엣지 도구 5종(PTM 포함), TruckType 정격용량, 도구 검사 순서, 스윕 기본 목적함수, 프론트엔드 줄 수, 클래스 수, 부록 B의 E-04·E-07·A-02 상태 등.
항목별 근거는 [specifications/README.md](specifications/README.md)에 남겨 두었다.

v1.8은 286개 문단, 58개 표로 구성되어 있으며(v1.7 대비 +27 문단·+4 표), 제목 계층과 SRS·SDD·TDD 구분을 구조적으로 확인했다. LibreOffice로 PDF 변환 후 페이지 렌더링을 시각 검증했고(개정 이력·설계 결정·엣지 신규 절·부록 B 포함), 원본 대비 XML 스키마 검증을 통과했다. 댓글과 변경 추적은 없다.

v1.7은 259개 문단, 54개 표, 1개 섹션으로 구성되어 있으며 제목 계층과 SRS·SDD·TDD 구분을 구조적으로 확인했다. 댓글과 변경 추적은 없다. 당시 환경에는 LibreOffice가 없어 DOCX 페이지 렌더링 기반 시각 검증은 수행하지 못했다.
