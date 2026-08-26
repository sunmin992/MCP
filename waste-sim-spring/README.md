# Waste Sim Spring

공동주택 생활폐기물 수거 정책과 엣지 장치 열·스로틀링을 실험하는 Spring Boot 기반 시뮬레이션 및 MCP 서버다.

## 문서

- [문서 인덱스](docs/README.md)
- [SRS·SDD·TDD 통합 명세서 (v1.11)](docs/specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_11.md) — 기준 문서
- [확인된 디버깅 항목](docs/reference/DEBUGGING_ISSUES.md)

문서 버전(명세서 v1.11)과 애플리케이션 버전(`pom.xml` 1.1.1)은 별개로 매긴다.
어느 명세로 빌드된 것인지는 `/actuator/info`가 두 값을 함께 알려준다 —
[버전 체계](docs/specifications/README.md#버전-체계--문서-버전과-애플리케이션-버전은-다르다) 참고.

## 실행

요구 환경은 Java 21이다. Maven은 래퍼가 받아 오므로 따로 설치하지 않아도 된다.

```powershell
.\mvnw.cmd spring-boot:run
```

기본 주소는 `http://localhost:8090`이다. 상세 설정은 [환경 설정 가이드](docs/guides/ENV_SETUP.md)를 참고한다.

## 검증

전체 테스트는 래퍼로 돌린다 — 개발 머신과 CI가 같은 Maven(3.9.14)을 쓰게 하려는 것이다.
로컬에 설치된 `mvn`을 쓰면 버전이 머신마다 달라 회귀 재현이 깨진다.

```powershell
.\mvnw.cmd -B test
```

macOS·Linux에서는 `./mvnw -B test`를 쓴다.
