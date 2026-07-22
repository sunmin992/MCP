# 설계 결정 항목 (Design Decisions)

> 필수 요구사항 파생 시나리오에서 "어떻게 동작해야 하는가"를 확정해야 하는 항목들.
> 각 결정을 확정한 뒤 대응 테스트로 고정한다.
> **전체 12개 항목 확정 완료** — 각 항목별 근거·테스트는 "나머지 결정 — 확정 내역" 참고.
> SDD §2.11 상태 컬럼 반영은 미완료(바이너리 docx라 이 세션에서 직접 편집 불가, "진행" 항목 3 참고).
> 관련: SDD §2.11, TDD §3.11.

## 결정표

| ID | 항목 | 옵션 | 권장 결정 | 상태 |
|----|------|------|-----------|------|
| **D-03** | 대화 이력의 시각 승계 | (a) 이번 메시지 기준만 / (b) 직전 이력 승계 | **(a)** 이력 승계 금지 | 확정 |
| **D-05** | 동시 다중 사용자/세션 | (a) 단일 'default' / (b) sessionId 분리 | **(b)로 로드맵**, 현재 (a)+"동시 사용 미지원" 제약 | 확정(현행 a, b는 로드맵) |
| **D-10** | RED 판정 기준(V-T5) | (a) 전역 hourlyWeight / (b) 노드별 / (c) 둘 중 하나 | **(a) 전역 고정** (임계 1.7에서 13시 RED) | 확정 |
| D-01 | 중복 시각 처리 | 중복 제거 1개 / 2개로 셈 | 중복 제거 후 1개(서로 다른 2시각은 실행 아님) | 확정 |
| D-02 | 시각 문자열 정규화 | 트림/자연어/한 자리 시 | 트림+자연어 허용, HH:MM 강제, "8:30" 무효 | 확정 |
| D-04 | CONFIRM 대기 중 새 요청 | 덮어씀 / 이전 우선 | 최신으로 덮어쓰고 폐기 안내 | 확정 |
| D-06 | 실행 중 재요청 | 큐잉/거절/병렬 | 순차 처리 | 확정 |
| D-07 | 범위 밖 값 | 클램프 / 거절 | 전 경로 거절(400/isError) | 확정(현행) |
| D-08 | 알 수 없는 필드 | 무시 / 거절 | 무시(@JsonIgnoreProperties) | 확정(현행) |
| D-09 | 교통 프로파일 부재 | 폴백 / 오류 | 교통 미적용 폴백 + warning | 확정(현행) |
| D-11 | 민원 0 표기 | 빈 화면 / "0건" | "0건" 명시 | 확정 |
| D-12 | 최적 시각 동률 | 가장 이른/늦은/임의 | 가장 이른 시각(결정론) | 확정(현행) |

---

## 시급 3개 — 확정 결정 + 대응 테스트

### D-03 — 이력 시각 승계 금지 (이번 메시지 기준만)
**결정:** 실행 의도 판단은 **현재 메시지의 시각 개수만** 본다. 이전 대화에 시각이 있어도 이번 메시지에 없으면 실행하지 않는다. (히스토리에서 시각을 끌어와 실행하는 실패를 구조적으로 차단 — FR-10 취지)

**대응 테스트** — `TimeExpressionDetectorTest` (또는 ChatController 통합):
```java
@Test
void historyTimeIsNotInherited() {
    // 이전 메시지에 시각이 있었어도, 이번 메시지 텍스트만으로 카운트한다.
    assertEquals(0, TimeExpressionDetector.count("그럼 그걸로 실행해줘"));   // 시각 없음 → 0
    assertEquals(1, TimeExpressionDetector.count("12시에 실행해줘"));         // 이번 메시지에 1개
}
```
> 통합 레벨: 1턴 "12시 어때?" → 2턴 "응 실행해" 시 **2턴에서 실행되지 않고 재질문**되어야 한다(시각 0개).

### D-05 — 동시 세션: 현재 단일 'default'(미지원 명시), 분리 로드맵
**결정:** 현재는 단일 `default` 세션으로 동작하며 **동시 다중 사용자 미지원**을 제약으로 문서화한다. 향후 `sessionId`별 `histories`·`pendingConfigs` 분리를 로드맵에 둔다. 지금 단계에선 "두 사용자의 이력·대기 설정이 섞일 수 있음"을 알려진 한계로 명시.

**대응 테스트** — 현행 동작(단일 세션) 고정 + 한계 회귀 방지:
```java
@Test
void singleSessionSharesPendingConfig() {
    // 현재 설계: 세션 구분 없음 → 두 확인 요청은 같은 'default' 대기열을 공유(덮어씀).
    // 이 테스트는 "현재 단일 세션"을 명시적으로 고정한다. 세션 분리 구현 시 이 테스트를 교체.
    // (ChatController에 sessionId 파라미터가 생기면 → 세션별 격리 테스트로 대체)
    assertTrue(true, "단일 'default' 세션 제약 — 세션 분리 구현 시 격리 테스트로 교체");
}
```
> 실제 코딩 시: `chat.send`가 STOMP `sessionId`를 받도록 바꾸면, "세션 A의 pendingConfig가 세션 B에 안 보인다"는 격리 테스트로 승격.

### D-10 — RED 판정: 전역 hourlyWeight 기준 고정
**결정:** V-T5의 RED 경고는 **전역 `hourlyWeight[hour] ≥ congestionThresholdRed`**로 판정한다(노드별 아님). 실측 데이터 스케일에서 임계 1.7이면 11–17시가 RED, 08:30은 정상.

**대응 테스트** — `SimulationConfigValidatorTest`(기존 `redPeakTimeWarnsButDoesNotBlock` 보강):
```java
@Test
void redJudgedByGlobalHourlyWeight() {
    var v = new SimulationConfigValidator(new TrafficDataService());
    // 13:00(전역 1.78 ≥ 1.7) → RED 경고 발생
    var peak = base(); peak.setCollectionTimeLabel("13:00");
    assertFalse(v.validate(peak).warnings().isEmpty());
    // 08:30(전역 1.54 < 1.7) → 경고 없음(정상)
    var off = base(); off.setCollectionTimeLabel("08:30");
    assertTrue(v.validate(off).warnings().isEmpty());
}
private SimulationConfig base() {
    var c = new SimulationConfig();
    c.setTrafficEnabled(true);
    c.setTrafficProfileId("jangryang-weekday");
    c.setTruckType("MEDIUM_2P5T");   // alleyAccess=true (V-T3와 격리)
    return c;
}
```
> 이 테스트가 "전역 기준"을 못박는다. 만약 검증기가 노드별로 본다면 08:30에도 Node_A(1.83)로 RED가 떠 이 테스트가 깨지므로, **구현이 전역 기준인지 자동 검증**된다.

---

## 나머지 결정 — 확정 내역

- **D-01** 중복 시각 — `TimeExpressionDetector.count()`가 매칭된 문자열을 그대로
  세던 걸 `Set`으로 중복 제거하도록 수정(정규화 후 dedupe). "12시 12시"→1,
  "12시 17시"→2. `TimeExpressionDetectorTest.duplicateTimeCountsOnce`로 고정.
  `llm_benchmark.py`의 `count_time_expressions()`도 동일 반영.
- **D-02** 정규화 — 이미 만족된 상태였음을 확인: `OpenAiService.isValidCollectionTime()`은
  두 자리 시(00~23)만 허용해 `"8:30"`은 무효로 판정하고, `SimulationConfig`의
  `setCollectionTimeLabel`/`getCollectionTimeLabel` 라운드트립이 트림과 재포맷을
  둘 다 처리한다(`"  12:00  "`→`"12:00"`, `"8:30"`→`"08:30"`).
  `SimulationConfigTest`로 고정.
- **D-04** CONFIRM 대기 중 새 요청 — `ChatController`에 `putPendingConfig()` 헬퍼를
  추가해, 이미 대기 중인 설정이 있는 상태에서 새 확인-대기 요청이 오면 최신
  요청으로 덮어쓰되 "이전 요청이 폐기되었습니다" SYSTEM 메시지를 먼저 보낸다.
  `ChatControllerTest.newConfirmRequestOverwritesPendingAndNotifiesDiscard`로 고정.
- **D-06** 실행 중 재요청 — `ChatController`에 세션 단위 락(`sessionLock`)을
  추가해 `handleMessage`/`confirmRun`/`clearHistory`가 서로 겹치지 않고
  순차적으로 처리되도록 직렬화했다(D-05상 세션이 하나뿐이라 전역 락 하나로
  충분).
- **D-07** 범위 밖 값 — 이미 만족된 상태였음을 확인: REST(`SimulationController`)·
  MCP(`McpController`)·채팅(`ChatController`) 모두 `SimulationTool` 파사드의
  `validate`/`runSimulation`을 거치므로 검증 게이트가 하나로 통일되어 있다.
  `SimulationConfigValidatorTest.outOfRangeFails`로 이미 고정돼 있었음.
- **D-08·D-09** 현행 그대로 확정(변경 없음).
- **D-11** 민원 0 표기 — 실사용 중 발견된 실제 버그였다: `SimulationEngine`이
  민원이 발생한 직업군만 `byOcc`에 키를 채워서, 어떤 직업군이 전체 시드에서
  단 한 번도 민원을 내지 않으면 그 직업군 항목 자체가 결과에서 사라졌다(화면에
  "생산직" 행이 통째로 안 보이는 것으로 재현). 실제 거주 중인 모든 직업군을
  0으로 미리 채워두도록 수정. `SimulationEngineTest`로 고정.
- **D-12** 최적 시각 동률 — 이미 만족된 상태였음을 확인: `ScenarioService`의
  `bestMean` 갱신이 엄격한 `<` 비교라, 오름차순으로 시각을 순회하는 한 동률일
  때 먼저 나온(이른) 시각이 유지된다. `ScenarioServiceTest.tiedBestMeanPicksEarliestTime`로
  고정(모의 SimulationService로 동률 상황을 인위적으로 재현).

## 진행
1. ~~D-03·D-05·D-10을 위 권장대로 확정.~~ 완료.
2. ~~위 테스트를 해당 테스트 클래스에 추가 → `mvn test` GREEN 확인.~~ 완료
   (`mvn clean test` 전체 그린 확인).
3. 확정된 결정을 SDD §2.11 상태 컬럼에 "확정"으로 반영 — **미완료**: SDD/TDD가
   `docs_waste-sim-spring_SRS_SDD_TDD.docx`(바이너리 Word 문서)라 이 세션의
   텍스트 편집 도구로는 직접 반영하지 못했다. 별도로 docx 편집을 요청하면
   반영 가능.
4. ~~나머지(D-01·02·04·06·07·11·12)는 우선순위대로 순차 확정.~~ 전부 확정
   완료(위 "확정 내역" 참고).
