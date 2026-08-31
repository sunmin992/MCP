# Waste Sim Spring

포항시 북구 장량동 원룸촌의 생활폐기물 수거 정책을 실험하는 Spring Boot 기반 DEVS
시뮬레이션 및 MCP 서버다. 자연어로 조건을 받아 서버가 필요한 값을 순서대로 물어
시뮬레이터를 구성하고 실행한다.

> **라즈베리파이 엣지 발열·냉각 도메인은 이 저장소에서 분리됐다.** 코드는 삭제됐고,
> 과거 이력은 `git log -- src/main/java/com/wastesim/edge/`로 조회할 수 있다.
> 명세는 `docs/specifications/raspberrypi_thermal_simulator_SRS_SDD_TDD_v1_0.md`에 남아 있다.

## 문서

- [문서 인덱스](docs/README.md)
- [장량동 시뮬레이터 명세서 (v2.0)](docs/specifications/jangnyang_simulator_SRS_SDD_TDD_v2_0.md) — 도메인 분리판
- [통합 명세서 (v1.13)](docs/specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_13.md) — 고정 서브태스크 계층
- [엣지 발열 명세서 (v1.0)](docs/specifications/raspberrypi_thermal_simulator_SRS_SDD_TDD_v1_0.md) — 분리된 도메인
- [확인된 디버깅 항목](docs/reference/DEBUGGING_ISSUES.md)

문서 버전(명세서 v1.11)과 애플리케이션 버전(`pom.xml` 1.1.1)은 별개로 매긴다.
어느 명세로 빌드된 것인지는 `/actuator/info`가 두 값을 함께 알려준다 —
[버전 체계](docs/specifications/README.md#버전-체계--문서-버전과-애플리케이션-버전은-다르다) 참고.

## 실행

요구 환경은 Java 21이다. Maven은 래퍼가 받아 오므로 따로 설치하지 않아도 된다.

```powershell
.\mvnw.cmd spring-boot:run
```

기본 주소는 `http://localhost:8090`이고, MCP 엔드포인트는 `POST /mcp` 하나다. 상세 설정은 [환경 설정 가이드](docs/guides/ENV_SETUP.md)를 참고한다.

## 검증

전체 테스트는 래퍼로 돌린다 — 개발 머신과 CI가 같은 Maven(3.9.14)을 쓰게 하려는 것이다.
로컬에 설치된 `mvn`을 쓰면 버전이 머신마다 달라 회귀 재현이 깨진다.

```powershell
.\mvnw.cmd -B test
```

macOS·Linux에서는 `./mvnw -B test`를 쓴다.
