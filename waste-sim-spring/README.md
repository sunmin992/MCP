# Waste Sim Spring

포항시 북구 장량동 원룸촌의 생활폐기물 수거 정책을 실험하는 Spring Boot 기반 DEVS
시뮬레이션 및 MCP 서버다. 자연어로 조건을 받아 서버가 필요한 값을 순서대로 물어
시뮬레이터를 구성하고 실행한다.

> **라즈베리파이 엣지 발열·냉각 도메인은 이 저장소에서 분리됐다.** 코드는 삭제됐고,
> 과거 이력은 `git log -- src/main/java/com/wastesim/edge/`로 조회할 수 있다.
> 명세는 `docs/specifications/raspberrypi_thermal_simulator_SRS_SDD_TDD_v1_0.md`에 남아 있다.

## 문서

- [문서 인덱스](docs/README.md)
- [통합 명세서 (파일 v1.15 · 문서 버전 2.3)](docs/specifications/waste-sim-spring_SRS_SDD_TDD_v1.15.md) — 기준 문서
- [엣지 발열 명세서 (v1.0)](docs/specifications/raspberrypi_thermal_simulator_SRS_SDD_TDD_v1_0.md) — 분리된 도메인
- [확인된 디버깅 항목](docs/reference/DEBUGGING_ISSUES.md)

기준 명세는 하나다. 도메인 분리(v2.0)·고정 서브태스크 계층(v2.1)·이동시간과 좌표
계층(v2.2)·고정 세트 v3(v2.3)로 이어진 판들이 **파일 `v1.15` · 문서 버전 `2.3`** 하나에
담겨 있고, 이 판의 수치·클래스명·도구명은 작업 트리와 대조한 것이다. 파일명 번호(1.15)와
문서 버전(2.3)이 다른 축이라는 점만 주의한다.

문서 버전과 애플리케이션 버전(`pom.xml` 1.1.1)은 별개 축으로 매긴다 —
[버전 체계](docs/specifications/README.md#버전-체계--문서-버전과-애플리케이션-버전은-다르다) 참고.
`/actuator/info`가 세 값을 함께 알려준다 — `app.version` 1.1.1, `spec.version` 1.15,
`spec.doc-version` 2.3. 표기는 "명세 v1.15(문서 2.3) / 앱 1.1.1"처럼 붙여 쓴다.

## 실행

요구 환경은 Java 21이다. Maven은 래퍼가 받아 오므로 따로 설치하지 않아도 된다.

```powershell
.\mvnw.cmd spring-boot:run
```

기본 주소는 `http://localhost:8090`이고, MCP 엔드포인트는 `POST /mcp` 하나다. 상세 설정은 [환경 설정 가이드](docs/guides/ENV_SETUP.md)를 참고한다.

## 시뮬레이터 구성

"시뮬레이터를 만들어 줘"류 요청은 한 문장으로 답할 수 없으므로, 서버가 질문을 소유하고
순서대로 물어 구성을 모은다. 질문 50개를 사용자 화면 8단계로 나누며, 관련 없는 항목도
생략하지 않고 "해당 없음"을 정식 답변으로 받는다 — 진행 표시의 분모가 끝까지 같아야
남은 질문 수를 알 수 있기 때문이다. 질문·순서·필수 여부는
[`subtask/jangnyang-simulator-v2.json`](src/main/resources/subtask/jangnyang-simulator-v2.json)이
소유하고 조회 경로에 LLM 호출이 없다. 정규화는 LLM이, 완료 판정과 실행 허용은 서버가 한다.

## 검증

전체 테스트는 래퍼로 돌린다 — 개발 머신과 CI가 같은 Maven(3.9.14)을 쓰게 하려는 것이다.
로컬에 설치된 `mvn`을 쓰면 버전이 머신마다 달라 회귀 재현이 깨진다.

```powershell
.\mvnw.cmd -B test
```

macOS·Linux에서는 `./mvnw -B test`를 쓴다. 현재 기준선은 **506건 통과·2건 스킵**이다 —
스킵 2건은 Python 참조 엔진(`adev-master/waste_sim`)이 없는 머신에서 나며 실패가 아니다.

브랜치를 크게 옮긴 직후에는 `clean`을 붙인다. `target/test-classes`에 남은 옛 테스트
클래스가 삭제된 클래스를 참조해 JUnit 탐색 자체가 깨지고, 실패 지점이 테스트가 아니라
`TestEngine ... failed to discover tests`로 나와 원인을 찾기 어렵다.

```powershell
.\mvnw.cmd -B clean test
```
