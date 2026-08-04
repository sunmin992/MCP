# Waste Sim Spring

공동주택 생활폐기물 수거 정책과 엣지 장치 열·스로틀링을 실험하는 Spring Boot 기반 시뮬레이션 및 MCP 서버다.

## 문서

- [문서 인덱스](docs/README.md)
- [SRS·SDD·TDD 통합 명세서 (v1.8)](docs/specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_8.docx)
- [확인된 디버깅 항목](docs/reference/DEBUGGING_ISSUES.md)

## 실행

요구 환경은 Java 21과 Maven이다.

```powershell
mvn spring-boot:run
```

기본 주소는 `http://localhost:8090`이다. 상세 설정은 [환경 설정 가이드](docs/guides/ENV_SETUP.md)를 참고한다.

