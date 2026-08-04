# 문서 인덱스

프로젝트 문서를 역할별로 분류한다. SRS·SDD·TDD의 기준은 통합 Word 명세서 한 개이며, Markdown 파일은 운영 가이드와 참고 기록으로만 사용한다.

## 기준 명세서

- [waste-sim-spring SRS·SDD·TDD 통합 명세서 v1.8](specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_8.docx) (이전 버전: [v1.7](specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_7.docx))

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

설계 결정 기록은 통합 명세서 v1.7 이후 코드와 함께 확정된 결정의 근거를 보존하므로 삭제하지 않는다. 디버깅 목록은 아직 명세서에 반영되지 않은 결함과 테스트 공백을 관리한다 — v1.8은 P1 4건(E-01, E-02, W-01, W-03)을 반영했고, 잔여 P2/P3 항목은 목록에 남아 있다.

## 문서 관리 규칙

- 요구사항·설계·테스트 기준은 통합 명세서에서 관리한다.
- 실행법, 데이터 연결과 실험 절차는 `guides/`에서 관리한다.
- 의사결정 근거, 자연어 예제와 결함 목록은 `reference/`에서 관리한다.
- 동일 내용을 별도 SRS/SDD/TDD Markdown으로 복제하지 않는다.
- 새 버전 명세서를 만들면 `specifications/`에 추가하고 이 인덱스의 기준 링크를 갱신한다.

## 검증 메모

v1.8은 286개 문단, 58개 표로 구성되어 있으며(v1.7 대비 +27 문단·+4 표), 제목 계층과 SRS·SDD·TDD 구분을 구조적으로 확인했다. LibreOffice로 PDF 변환 후 페이지 렌더링을 시각 검증했고(개정 이력·설계 결정·엣지 신규 절·부록 B 포함), 원본 대비 XML 스키마 검증을 통과했다. 댓글과 변경 추적은 없다.

v1.7은 259개 문단, 54개 표, 1개 섹션으로 구성되어 있으며 제목 계층과 SRS·SDD·TDD 구분을 구조적으로 확인했다. 댓글과 변경 추적은 없다. 당시 환경에는 LibreOffice가 없어 DOCX 페이지 렌더링 기반 시각 검증은 수행하지 못했다.
