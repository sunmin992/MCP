# 설계도 경로 LLM 벤치마크 — 설계

2026-09-06 · 기준 커밋 `05bb4ee`

여러 LLM 모델을 같은 표에서 비교한다. 재는 대상은 **설계도 경로에서 LLM이 실제로 하는 일 하나** —
`RequestInterpreter.extract`다.

## 1. 왜 다시 만드는가

`llm_benchmark.py`(1,415줄)를 읽고 확인한 것이다.

**낡았다.**

- **섹션 1**(150여 줄)은 파일 스스로 "폐기된 단일 `SYSTEM_PROMPT` ... 더 이상 운영 코드에 없음"이라
  적어 둔 구조를 잰다.
- **엣지 도메인**(라즈베리파이·발열·FPS)이 테스트 18개 중 2개와 도메인 라우팅 섹션 전체를 차지한다.
  그 도메인은 2026-08-31에 분리됐다.
- **접지성/할루시네이션 섹션**은 파일 주석이 밝히듯 "실제 앱은 결과를 코드가 템플릿으로 채우므로 이
  위험이 없다"는 기능을 잰다. 없는 기능의 안전성을 재고 있다.
- 곳곳에 **논문 인용용**이라는 목적이 적혀 있다. 그 틀은 2026-09-06에 걷어냈다.

**빠졌다.**

지금 아키텍처인 설계도 경로를 재는 것이 하나도 없다. `RequestInterpreter`(field·value·span JSON) →
`SpanVerifier`(인용 검증) → `FeasibilityGate`(거부 판정) → `GapResolver`(자동 채움/되묻기) 어느 것도
측정 대상이 아니다. **이 설계의 안전장치인 인용 검증이 한 번도 측정된 적이 없다.**

**구조적으로 위험하다.**

Java의 프롬프트·정규식·`JailbreakFilter` 로직이 전부 파이썬에 손으로 복사돼 있다. 이 위험은 이미
명세에 `NFR-12`(측정 하니스 정합성)로 적혀 있고, `BenchmarkFilterParityTest`가 사본을 대조해
지키고 있다 — **사본이 있다는 사실 자체가 그 테스트의 존재 이유다.**

## 2. 무엇을 재는가

이 경로에서 LLM이 하는 일은 딱 하나다. `RequestInterpreter.extract` 한 번의 호출이고, 그 뒤는 전부
결정론적 Java다. 그래서 **그 한 번의 호출과, 그것이 결정론 단계를 통과하며 낳는 결과**를 잰다.

| 축 | 재는 것 | 왜 |
|---|---|---|
| 형식 준수율 | `requireWellFormed` 통과 | 실패하면 폴백 — 34문항 전체로 되돌아간다. `faf2efd` 이전에는 이것이 100% 실패였고 아무도 몰랐다 |
| 재현율(놓침) | 문장에 있는 필드를 뽑았는가 | 놓친 만큼 사용자가 더 답한다 |
| 정밀도(창작) | 문장에 없는 필드를 뽑았는가 | |
| 정규화 통과율 | "한 달치"→30이 `SpanVerifier`를 살아서 통과하는가 | 검사가 너무 빡빡하면 정상 값이 버려진다 |
| **창작 차단율** | 지어낸 값을 `SpanVerifier`가 잡는가 | **이 설계의 안전장치.** 지금 아무도 재지 않는다 |
| 거부 판정 일치 | `FeasibilityGate` verdict가 라벨과 같은가 | 과잉거절과 누락을 따로 센다 |
| 미지 필드율 | 카탈로그에 없는 field를 냈는가 | 지금은 `idOfField`가 조용히 버린다 |
| 문항 감소 | 34 → N | |
| 지연 | 초 | |

**문항 감소는 단독으로 보고하지 않는다.** 창작을 많이 하는 모델일수록 이 숫자가 좋아 보인다 —
반드시 창작 차단율과 같은 표에 둔다. 이 규칙을 어기면 표가 나쁜 모델을 추천하게 된다.

## 3. 케이스를 서브태스크에 묶는다

```java
record BenchmarkCase(String request,
                     Map<String, Object> expected,       // answerField → 기대값
                     Set<String> forbidden,              // 뽑으면 창작
                     FeasibilityVerdict.Reason refusal,  // null이면 통과해야 한다
                     String why)                         // 이 케이스가 있는 이유
```

**`expected`·`forbidden`의 키는 카탈로그에 실재하는 `answerField`여야 한다.** 없는 이름을 쓰면
벤치마크가 시작조차 못 하게 막는다. 문항 세트가 v5로 가면 낡은 케이스가 조용히 통과하는 대신 즉시
드러난다 — 이것이 "실험용 테스크를 서브태스크로 바꾼다"의 실질이다.

`why`는 선택 항목이 아니다. 지금 `PROMPTS`의 가치 대부분은 각 줄에 붙은 "이 케이스가 왜 있는가"
주석에 있다(순우리말 시각을 못 읽어 모델이 가짜 결과를 지어냈던 일 등). 그 기록을 레코드 필드로
올려 잃지 않는다.

### 케이스 6종, 20~25개

1. **직접 인용** — "26개 동", "30일", "시드 10회"
2. **정규화** — "한 달치"→30 · "아침 여덟시 반"→`08:30` · "일주일"→7
3. **창작 함정** — "적당히 알아서 돌려줘" → `forbidden` = 전 필드
4. **거부 4종** — 부산(`OUT_OF_REGION`) · 다른 도메인 · 데이터 없는 결론 · 시뮬레이션 아님
5. **실행 동사** — "26개 동으로 한 달 돌려줘"(`05bb4ee`에서 만든 경로)
6. **숫자 오배치 함정** — "26개 동에 30명씩 7일"(셋이 서로 다른 필드)

기존 `PROMPTS`의 LLM 추출 사례는 **전부 서브태스크 필드로 옮겨진다** — 용량 배정은
`routeAvailableCapacityKg`(21)·`initialTruckLoadKg`(22), 방문 순서는 `routeSequence`(31), 엔진
지정은 `engine`(3), 트럭은 `truckType`(19)·`truckCount`(20)이다. 잃는 측정이 없다.

정확한 개수는 구현 계획에서 확정한다. 반복은 `RUNS=3`을 유지한다. 같은 문장에서 모델이 흔들리는 정도가 실제로 문제였다.

## 4. 실행과 출력

```
src/test/java/com/wastesim/benchmark/
  BlueprintBenchmark.java    실행·집계 (@Tag("benchmark"))
  BenchmarkCase.java         케이스 레코드 + 카탈로그 검증
  BenchmarkCases.java        케이스 목록
  BenchmarkReport.java       Markdown 생성
```

**모델별 인스턴스화** — 프로덕션을 바꾸지 않는다. `OpenAiService`는 필드 주입(`@Value`)이라 생성자가
없으므로 `ReflectionTestUtils`로 세 필드만 채운다.

```java
OpenAiService svc = new OpenAiService();
ReflectionTestUtils.setField(svc, "apiUrl", m.url());
ReflectionTestUtils.setField(svc, "apiKey", m.key());
ReflectionTestUtils.setField(svc, "model",  m.name());
RequestInterpreter interp = new OpenAiRequestInterpreter(svc);   // 진짜 프로덕션 클래스
```

이것이 이 설계의 핵심이다. **사본을 대조하는 것이 아니라 사본이 없다.** 문항 정의는
`JangnyangSubtaskCatalog`가 읽어 주고, 인용 검증·거부 판정·자동 채움은 프로덕션 클래스가 그대로
수행한다.

**실행**

```bash
./mvnw test -Dgroups=benchmark
```

평소 `mvn test`에서는 제외된다 — `pom.xml`의 surefire에 `<excludedGroups>benchmark</excludedGroups>`를
추가한다(현재 surefire 설정이 없으므로 새로 만든다). 모델 목록은 `BENCHMARK_MODELS` 환경변수로
덮어쓰고, `EXCLUDE_MODELS`와 OpenAI 키 미설정 시 건너뛰기는 지금 동작을 유지한다.

**ollama가 떠 있지 않으면 실패가 아니라 사유를 밝히고 건너뛴다.** 측정을 못 한 것과 측정 결과가
나쁜 것은 다르다 — 둘을 같은 신호로 내면 "빨간불"이 무엇을 뜻하는지 알 수 없게 된다.

**출력** — 파일명을 유지한다(둘 다 이미 `.gitignore`에 있다).

- `benchmark_report.md` — 모델이 행, 2절의 축이 열
- `benchmark_detail.log` — 실패 케이스의 모델 원문 응답. 진단에 필요하다

## 5. 옮기고 지우는 것

**옮긴다** → `ExecutionRoutingRegressionTest`(JUnit, LLM 미사용)

순우리말 시각·방문 순서·비교 요청·엔진 키워드 등 **판정** 사례. `TimeExpressionDetector`·
`ExecutionIntentDetector`·`EngineSelectionDetector`는 결정론이라 모델과 무관하다 — 지금 보고서에도
도메인 라우팅이 "전 모델 공통"이라고 적혀 있다. 모델별로 재는 것이 의미가 없고, JUnit으로 옮기면
`mvn test`에서 매번 돌아 ollama 없이도 지켜진다.

**지운다**

- `llm_benchmark.py`
- `BenchmarkFilterParityTest` — **`NFR-12`가 구조적으로 충족되기 때문이다.** 그 요구사항은 "벤치마크의
  방어 판정이 실제 `JailbreakFilter`와 동일해야 한다"인데, 이제 사본을 대조하는 대신 실제 클래스를
  호출한다. 대조할 사본이 없다. 요구사항을 버리는 것이 아니라 **지킬 필요가 없는 형태로 바꾸는
  것**이다.
- 엣지 도메인 사례 2개와 도메인 라우팅 섹션
- 접지성/할루시네이션 섹션

**갱신한다**

`docs/guides/ENV_SETUP.md` §3-1 · `docs/reference/LLM_BENCHMARK_GUIDE.md` ·
`docs/reference/DESIGN_DECISIONS.md:96`

**손대지 않는다**

`waste-sim-spring_SRS_SDD_TDD_v1.15.md`에 네 곳이 걸려 있다(307·1381·1401·1667행). 판본이 붙은
명세라 새 판을 내는 절차가 따로 있고, 문서 v2.4가 이미 미커밋으로 대기 중이다. 무엇을 고쳐야 하는지만
정리해 남긴다.

## 6. 재지 않기로 한 것과 그 이유

- **접지성/할루시네이션** — 결과 서술을 LLM이 하지 않는다. 코드가 템플릿으로 채운다. LLM 해설 기능을
  넣기로 하면 그때 다시 만든다.
- **즉시 실행 경로의 모델별 정확도** — 판정이 결정론이라 모델과 무관하다. 5절대로 JUnit으로 옮긴다.
- **Jailbreak 방어** — 유지한다. `JailbreakFilter`는 프로덕션에 살아 있다. 다만 사본이 아니라 실제
  클래스를 호출한다.

## 7. 되돌리는 방법

새 벤치마크는 테스트 태그 하나에 얹혀 있다. `-Dgroups=benchmark`를 주지 않으면 아무것도 돌지 않고,
프로덕션 코드는 한 줄도 바뀌지 않는다. 되돌릴 일이 생기면 `src/test/java/com/wastesim/benchmark/`를
지우고 surefire 설정 한 줄을 빼면 된다.

지워진 `llm_benchmark.py`는 git 이력에 남는다 — `git show <이 변경 이전 커밋>:waste-sim-spring/llm_benchmark.py`.

## 8. 남는 것

- `SRS_SDD_TDD` 명세 네 곳 갱신(새 판본 절차 필요)
- Python 참조 엔진과 Java 엔진의 **결과 대조는 여전히 없다.** 두 엔진을 비교하는 테스트가 하나도
  없고, Python 쪽은 교통 프로파일을 자기 CSV에서 따로 뽑아 쓴다. 이 벤치마크의 범위 밖이지만, "참조
  구현"이라는 이름이 아직 실현되지 않았다는 사실은 여기 적어 둔다.
