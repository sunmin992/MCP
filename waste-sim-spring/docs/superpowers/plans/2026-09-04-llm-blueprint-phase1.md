# LLM 설계도 구성 1단계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **이후 변경 (2026-09-06):** 이 계획이 지시한 `BasisKind.PAPER`는 제거됐고
> `UNVERIFIED`는 `MODEL_DEFAULT`로, `DataQualityFlag.DEFAULT_BASIS_UNVERIFIED`는
> `MODEL_DEFAULT_USED`로 바뀌었다. 저장소에 논문이 없어 대조할 대상이 없고, "확인하지
> 않았다"는 이름이 있지도 않은 숙제를 가리켰기 때문이다. **아래 본문은 실행 당시의 기록
> 그대로 두었다** — 고쳐 쓰면 무엇을 지시했고 무엇이 바뀌었는지 알 수 없게 된다.
> 현행 설계는 스펙 문서를 본다.

**Goal:** 사용자의 자유 문장에서 LLM이 시뮬레이션 설계도를 채우고, 근거 없는 값은 지어내지 않고 되묻고, 만들 수 없는 요청은 무엇이 필요한지와 함께 거부한다.

**Architecture:** LLM은 값을 뽑기만 하고(`RequestInterpreter`) 판정은 전부 결정적 코드가 한다(`SpanVerifier`, `FeasibilityGate`, `GapResolver`). LLM이 뽑은 값은 기존 `JangnyangSubtaskValidator`를 그대로 통과해야 하며 원장에 `LLM_NORMALIZED`로 남는다. 필드마다 근거(`FieldBasis`)를 선언해 자동 채움과 되묻기를 데이터로 가른다.

**Tech Stack:** Java 21, Spring Boot 3.2.0, Jackson (record 역직렬화), JUnit 5, Maven Wrapper

**Spec:** `waste-sim-spring/docs/superpowers/specs/2026-09-04-llm-blueprint-design.md`

## Global Constraints

- 작업 디렉터리는 `C:\Dev\MCP\waste-sim-spring`. 모든 명령은 여기서 실행한다.
- 테스트 실행은 `./mvnw test`. 단일 클래스는 `./mvnw test -Dtest=클래스명`, 단일 메서드는 `./mvnw test -Dtest=클래스명#메서드명`.
- 시작 상태: 546개 테스트 통과, 기준 커밋 `dbd99a3`.
- **기존 33문항 흐름의 결과를 바꾸지 않는다.** 기존 테스트 546개가 계속 통과해야 한다.
- **조용한 폴백 금지.** 값을 모를 때 기본값으로 채우려면 근거 선언이 있어야 하고, 없으면 되묻거나 막는다.
- **검증 로직을 두 벌로 만들지 않는다.** LLM 값도 `JangnyangSubtaskValidator`를 통과한다.
- 서브태스크 세트 JSON은 `immutable: true`다. 내용을 바꾸면 **버전을 올린다**(D-45). v3는 손대지 않고 v4를 만든다.
- 주석과 커밋 메시지는 한국어로 쓴다. "무엇을" 이 아니라 **"왜"** 를 적는다.
- 새 방어 하나마다 **변이 테스트**로 확인한다: 그 방어를 무력화했을 때 테스트가 실패해야 한다.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `subtask/BasisKind.java` (신규) | 근거 여섯 종류와 "자동 채움 가능한가" 판정 |
| `subtask/FieldBasis.java` (신규) | 필드 하나의 근거 선언 — 종류·기본값·출처 |
| `subtask/JangnyangSubtask.java` (수정) | `basis` 성분 추가 |
| `subtask/JangnyangSubtaskValidator.java` (수정) | 출처를 인자로 받는다 (지금 `USER_DIRECT` 하드코딩 12곳) |
| `subtask/SubtaskSessionService.java` (수정) | `submit`에 출처 오버로드 |
| `subtask/GapResolver.java` (신규) | 미충족 필드를 근거로 갈라 자동 채움 / 되묻기 목록을 낸다 |
| `model/DataQualityFlag.java` (수정) | `DEFAULT_BASIS_UNVERIFIED` 추가 |
| `llm/ExtractedValue.java` (신규) | LLM이 뽑은 값 하나 — 필드·값·인용 조각 |
| `llm/RequestExtraction.java` (신규) | 추출 결과 전체 — 설계도 값 + 판정용 필드 |
| `llm/SpanVerifier.java` (신규) | 인용 조각이 요청에 실제로 있는지 검사 |
| `llm/RequestInterpreter.java` (신규) | 인터페이스. 문장 → `RequestExtraction` |
| `llm/OpenAiRequestInterpreter.java` (신규) | LLM 구현체 |
| `llm/FeasibilityGate.java` (신규) | 거부 판정 |
| `llm/FeasibilityVerdict.java` (신규) | 거부 응답 — 사유 + 부족한 것 목록 |
| `main/resources/subtask/jangnyang-simulator-v4.json` (신규) | v3 + 필드별 `basis` 선언 |

---

## Task 1: 답변 출처를 검증기까지 전달한다

`SubtaskAnswerSource.LLM_NORMALIZED`는 enum에 값이 있고 `JangnyangScenarioSpec`이 그것을 읽는 코드까지 있는데 **넣는 곳이 없다.** `JangnyangSubtaskValidator`가 12곳에서 `USER_DIRECT`를 하드코딩한다. LLM이 채운 값을 원장에서 구별할 수 없으면, 나중에 "이 값을 누가 넣었나"를 되짚을 수 없다.

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/JangnyangSubtaskValidator.java`
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java:75`
- Test: `src/test/java/com/wastesim/subtask/AnswerSourceTest.java` (신규)

**Interfaces:**
- Produces:
  - `SubtaskValidationResult JangnyangSubtaskValidator.validate(JangnyangSubtaskDefinition def, Map<String,Object> answers, Map<String,JangnyangSubtaskAnswer> existing, SubtaskAnswerSource source)`
  - 기존 3인자 `validate(def, answers, existing)`는 남기고 `USER_DIRECT`로 위임한다
  - `SubtaskSessionService.Step submit(String sessionKey, String subtaskId, Object value, Integer version, SubtaskAnswerSource source)`
  - 기존 4인자 `submit(sessionKey, subtaskId, value, version)`는 남기고 `USER_DIRECT`로 위임한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/AnswerSourceTest.java`:

```java
package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변 원장에 출처가 남는가.
 *
 * <p>{@code LLM_NORMALIZED}는 enum에 값만 있고 넣는 곳이 없었다. 검증기가 12곳에서
 * {@code USER_DIRECT}를 하드코딩했기 때문이다. 출처를 구별하지 못하면 나중에 "이 값을
 * 누가 넣었나"를 되짚을 수 없고, LLM이 채운 값과 사람이 답한 값이 섞인다.
 */
class AnswerSourceTest {

    private static SubtaskSessionService service() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        return new SubtaskSessionService(catalog, new InMemorySubtaskSessionStore(),
                new JangnyangSubtaskValidator(), new JangnyangCompletenessChecker());
    }

    /** LLM이 넣은 값은 원장에 LLM_NORMALIZED로 남아야 한다. */
    @Test
    void llmAnswerIsRecordedAsLlmNormalized() {
        SubtaskSessionService svc = service();
        svc.start("s1");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s1"));
        String firstId = def.subtasks().get(0).id();

        svc.submit("s1", firstId, "민원 발생량 확인", null, SubtaskAnswerSource.LLM_NORMALIZED);

        JangnyangSubtaskAnswer a = svc.activeSession("s1").answers().get(firstId);
        assertNotNull(a, "답변이 원장에 없다");
        assertEquals(SubtaskAnswerSource.LLM_NORMALIZED, a.source(),
                "LLM이 넣은 값을 사용자 답변과 구별할 수 없으면 출처를 되짚을 수 없다");
    }

    /** 출처를 주지 않은 기존 호출은 USER_DIRECT로 남아야 한다 — 기존 동작 불변. */
    @Test
    void omittingSourceStaysUserDirect() {
        SubtaskSessionService svc = service();
        svc.start("s2");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s2"));
        String firstId = def.subtasks().get(0).id();

        svc.submit("s2", firstId, "민원 발생량 확인", null);

        assertEquals(SubtaskAnswerSource.USER_DIRECT,
                svc.activeSession("s2").answers().get(firstId).source(),
                "기존 경로의 출처가 바뀌면 이미 쌓인 원장의 의미가 달라진다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=AnswerSourceTest`
Expected: 컴파일 실패 — `submit(String, String, Object, Integer, SubtaskAnswerSource)` 메서드가 없다

- [ ] **Step 3: 검증기가 출처를 인자로 받게 고친다**

`JangnyangSubtaskValidator.java`에서 기존 3인자 `validate`를 4인자로 바꾸고 3인자 오버로드를 남긴다:

```java
    /**
     * 출처를 주지 않으면 사용자가 직접 답한 것으로 본다. 기존 호출부의 동작을 그대로
     * 유지하기 위한 것이며, 이미 쌓인 원장의 의미를 바꾸지 않는다.
     */
    public SubtaskValidationResult validate(JangnyangSubtaskDefinition def,
                                            Map<String, Object> answers,
                                            Map<String, JangnyangSubtaskAnswer> existing) {
        return validate(def, answers, existing, SubtaskAnswerSource.USER_DIRECT);
    }

    /**
     * 이 답변들을 누가 넣었는지를 원장에 남긴다.
     *
     * <p>LLM이 채운 값과 사람이 답한 값은 <b>같은 검증을 받지만 출처가 다르다.</b> 검증은
     * 같아야 하고(LLM 값에 예외를 두면 근거 없는 값이 흘러든다) 출처는 달라야 한다
     * (나중에 "이 값을 누가 넣었나"를 되짚어야 한다).
     */
    public SubtaskValidationResult validate(JangnyangSubtaskDefinition def,
                                            Map<String, Object> answers,
                                            Map<String, JangnyangSubtaskAnswer> existing,
                                            SubtaskAnswerSource source) {
        // 이하 기존 본문. 아래 Step 4에서 USER_DIRECT를 source로 치환한다.
```

- [ ] **Step 4: 하드코딩된 `USER_DIRECT` 12곳을 `source`로 치환한다**

`USER_DIRECT`를 쓰는 지점들이 4인자 `validate`의 본문 안(또는 그것이 부르는 private 메서드) 이므로, `source`를 그 private 메서드들에 인자로 전달한다. 치환 전에 개수를 확인하고 치환 후 0이 되는지 확인한다:

```bash
grep -c "SubtaskAnswerSource.USER_DIRECT" src/main/java/com/wastesim/subtask/JangnyangSubtaskValidator.java
# 치환 후에는 3인자 오버로드의 1곳만 남아야 한다
```

- [ ] **Step 5: 세션 서비스에 출처 오버로드를 더한다**

`SubtaskSessionService.java`:

```java
    /** 출처를 주지 않으면 사용자가 직접 답한 것으로 본다. */
    public Step submit(String sessionKey, String subtaskId, Object value, Integer version) {
        return submit(sessionKey, subtaskId, value, version, SubtaskAnswerSource.USER_DIRECT);
    }

    public Step submit(String sessionKey, String subtaskId, Object value, Integer version,
                       SubtaskAnswerSource source) {
        // 기존 본문. validator.validate(...) 호출에 source를 넘긴다:
        //   validator.validate(def, Map.of(targetId, value == null ? "" : value),
        //                      session.answers(), source);
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=AnswerSourceTest`
Expected: PASS (2 tests)

- [ ] **Step 7: 기존 테스트가 깨지지 않았는지 확인한다**

Run: `./mvnw test`
Expected: 548 tests, 0 failures (546 + 신규 2)

- [ ] **Step 8: 변이로 방어를 확인한다**

`submit`의 5인자 오버로드에서 `source`를 `USER_DIRECT`로 바꿔 넣고 테스트를 돌린다:

```bash
# 임시로 source 대신 USER_DIRECT를 넘기도록 바꾼 뒤
./mvnw test -Dtest=AnswerSourceTest
# Expected: llmAnswerIsRecordedAsLlmNormalized 실패
# 확인 후 되돌린다
```

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/wastesim/subtask/JangnyangSubtaskValidator.java \
        src/main/java/com/wastesim/subtask/SubtaskSessionService.java \
        src/test/java/com/wastesim/subtask/AnswerSourceTest.java
git commit -m "feat(subtask): 답변 출처를 검증기까지 전달해 LLM이 넣은 값을 구별한다

LLM_NORMALIZED는 enum에 값이 있고 ScenarioSpec이 읽는 코드까지 있는데 넣는 곳이 없었다.
검증기가 12곳에서 USER_DIRECT를 하드코딩했기 때문이다. 출처를 구별하지 못하면 나중에
이 값을 누가 넣었는지 되짚을 수 없고, LLM이 채운 값과 사람이 답한 값이 섞인다.

검증은 같게 두고 출처만 갈랐다 — LLM 값에 검증 예외를 두면 근거 없는 값이 흘러든다.
출처를 주지 않는 기존 호출은 USER_DIRECT로 위임하므로 이미 쌓인 원장의 의미는 그대로다."
```

---

## Task 2: 필드별 근거를 선언한다

"근거 유무로 가른다"를 판단이 아니라 **기계가 읽는 데이터**로 만든다. 지금 근거는 코드 주석에 흩어져 있다 — `wasteMeanKg=0.9`가 논문에서 왔다는 사실이 공통 주석에만 있고, `days`·`seeds`는 아무 표시도 없다.

**Files:**
- Create: `src/main/java/com/wastesim/subtask/BasisKind.java`
- Create: `src/main/java/com/wastesim/subtask/FieldBasis.java`
- Modify: `src/main/java/com/wastesim/subtask/JangnyangSubtask.java:32-64`
- Modify: `src/main/java/com/wastesim/subtask/JangnyangSubtaskCatalog.java:39` (`SET_RESOURCES`에 v4 추가)
- Create: `src/main/resources/subtask/jangnyang-simulator-v4.json`
- Test: `src/test/java/com/wastesim/subtask/FieldBasisTest.java`

**Interfaces:**
- Consumes: Task 1의 변경 없음 (독립)
- Produces:
  - `enum BasisKind { PAPER, REGULATION, MEASURED, UNVERIFIED, NONE, EXPERIMENT_INTENT }`
    - `boolean canFillWithoutAsking()` — `NONE`·`EXPERIMENT_INTENT`만 `false`
    - `boolean needsUnverifiedWarning()` — `UNVERIFIED`만 `true`
  - `record FieldBasis(BasisKind kind, Object value, String source, String why)`
    - `static FieldBasis unknown()` — 선언이 없는 필드용, `kind = UNVERIFIED`가 아니라 `NONE`
  - `JangnyangSubtask.basis()` — 선언이 없으면 `null`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/FieldBasisTest.java`:

```java
package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 필드별 근거 선언.
 *
 * <p>"근거 유무로 가른다"를 판단이 아니라 데이터로 만든다. 지금 근거는 코드 주석에 흩어져
 * 있어서 기계가 읽을 수 없다 — {@code wasteMeanKg=0.9}가 논문에서 왔다는 사실이 공통 주석에만
 * 있고 {@code days}·{@code seeds}는 아무 표시도 없다.
 *
 * <p>이 테스트가 지키는 것은 <b>선언이 실제 상태를 말하는가</b>다. 확인하지 않은 출처를
 * {@code PAPER}로 적으면 이 설계가 막으려는 실수를 설계 자체가 저지르는 것이 된다.
 */
class FieldBasisTest {

    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    /** 자동 채움 가능 여부는 두 종류만 거짓이다. */
    @Test
    void onlyTwoKindsBlockAutomaticFilling() {
        assertTrue(BasisKind.PAPER.canFillWithoutAsking());
        assertTrue(BasisKind.REGULATION.canFillWithoutAsking());
        assertTrue(BasisKind.MEASURED.canFillWithoutAsking());
        assertTrue(BasisKind.UNVERIFIED.canFillWithoutAsking());
        assertFalse(BasisKind.NONE.canFillWithoutAsking(),
                "근거 없는 값을 기본값으로 채우면 조용한 가정이 된다");
        assertFalse(BasisKind.EXPERIMENT_INTENT.canFillWithoutAsking(),
                "실험 목적은 사용자가 정해야 한다");
    }

    /** 경고가 필요한 것은 UNVERIFIED뿐이다 — 경고를 남발하면 읽히지 않는다. */
    @Test
    void onlyUnverifiedNeedsAWarning() {
        assertTrue(BasisKind.UNVERIFIED.needsUnverifiedWarning());
        for (BasisKind k : BasisKind.values()) {
            if (k != BasisKind.UNVERIFIED) {
                assertFalse(k.needsUnverifiedWarning(), k + "에 출처 미확인 경고를 붙이면 안 된다");
            }
        }
    }

    /** v4의 33문항 전부에 선언이 있어야 한다 — 빠진 필드는 조용히 처리된다. */
    @Test
    void everyV4SubtaskDeclaresItsBasis() {
        List<String> missing = v4().subtasks().stream()
                .filter(s -> s.basis() == null)
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), missing, "근거 선언이 없는 필드");
        assertEquals(33, v4().subtasks().size());
    }

    /**
     * 근거 없는 필드는 정확히 셋이다. 2026-09-02에 하루 종일 경고 표시를 붙인 값들이며,
     * 하나가 늘거나 줄면 이 서술을 다시 세워야 한다.
     */
    @Test
    void exactlyThreeFieldsHaveNoBasis() {
        List<String> none = v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.NONE)
                .map(JangnyangSubtask::answerField)
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of("intraZoneTravelMinutes", "serviceMinutesPerSite",
                        "zoneAssignmentRule"),
                none);
    }

    /** 채울 수 있다고 선언한 필드는 기본값을 함께 내야 한다 — 없으면 채울 것이 없다. */
    @Test
    void fillableFieldsCarryAValue() {
        List<String> broken = v4().subtasks().stream()
                .filter(s -> s.basis().kind().canFillWithoutAsking())
                .filter(s -> s.basis().value() == null)
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), broken, "자동 채움이라면서 기본값이 없는 필드");
    }

    /** 출처를 주장하는 필드는 출처 문자열을 함께 내야 한다. */
    @Test
    void citedFieldsCarryTheirSource() {
        List<String> broken = v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.PAPER
                        || s.basis().kind() == BasisKind.REGULATION
                        || s.basis().kind() == BasisKind.MEASURED)
                .filter(s -> s.basis().source() == null || s.basis().source().isBlank())
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), broken, "출처를 주장하면서 출처 문자열이 없는 필드");
    }

    /** 근거 없다고 선언한 필드는 그 이유를 적어야 한다. */
    @Test
    void unbasedFieldsExplainWhy() {
        v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.NONE)
                .forEach(s -> assertFalse(s.basis().why() == null || s.basis().why().isBlank(),
                        s.answerField() + "에 근거 없는 이유가 적혀 있지 않다"));
    }

    /** v3는 손대지 않는다 — immutable 세트의 내용이 바뀌면 세트 해시가 무의미해진다. */
    @Test
    void v3IsUntouched() {
        JangnyangSubtaskDefinition v3 = new JangnyangSubtaskCatalog().byVersion(3);
        assertEquals(33, v3.subtasks().size());
        assertTrue(v3.subtasks().stream().allMatch(s -> s.basis() == null),
                "v3에 basis가 생기면 immutable 세트를 수정한 것이다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=FieldBasisTest`
Expected: 컴파일 실패 — `BasisKind`, `FieldBasis`, `JangnyangSubtask.basis()`가 없다

- [ ] **Step 3: `BasisKind`를 만든다**

`src/main/java/com/wastesim/subtask/BasisKind.java`:

```java
package com.wastesim.subtask;

/**
 * 이 필드의 기본값이 <b>무엇에 근거하는가</b>.
 *
 * <p>"근거 유무로 가른다"를 판단이 아니라 데이터로 만들기 위한 것이다. 사람이 매번
 * "이건 물어봐야 하나"를 정하면 기준이 흔들리고, 흔들린 기준은 결국 근거 없는 값을
 * 통과시킨다 — 이 프로젝트가 지어낸 좌표로 겪은 일이다.
 *
 * <p>동작은 셋뿐이다: 자동으로 채운다 / 채우되 표시한다 / 반드시 묻는다.
 */
public enum BasisKind {

    /** 논문 DEVS 모델에서 온 값. 출처(절·표)를 함께 적는다. */
    PAPER,

    /** 포항시 규정·표준데이터에서 온 값. */
    REGULATION,

    /** 우리가 측정하거나 산출한 값(OSRM·TMAP). */
    MEASURED,

    /**
     * 기본값은 있는데 <b>출처를 확인하지 않았다.</b>
     *
     * <p>채우기는 하지만 결과에 표시를 붙인다. 보기 싫은 표시지만 사실이고, 출처를 확인해
     * 승격시키는 만큼 줄어든다 — 남은 일이 결과에 드러나는 구조다.
     */
    UNVERIFIED,

    /**
     * 근거가 없다. <b>반드시 묻는다.</b>
     *
     * <p>기본값을 두면 아무 값도 주지 않은 실행이 조용히 그 가정을 쓴다. 다른 미측정 입력을
     * 모두 막는 쪽으로 처리해 왔으므로(V-T6·V-T7) 여기서도 막는다.
     */
    NONE,

    /** 값이 아니라 사용자가 정해야 할 실험 목적. 채울 수 있는 성질이 아니다. */
    EXPERIMENT_INTENT;

    /** 묻지 않고 채울 수 있는가. */
    public boolean canFillWithoutAsking() {
        return this != NONE && this != EXPERIMENT_INTENT;
    }

    /** 채우되 출처 미확인 표시를 붙여야 하는가. */
    public boolean needsUnverifiedWarning() {
        return this == UNVERIFIED;
    }
}
```

- [ ] **Step 4: `FieldBasis`를 만든다**

`src/main/java/com/wastesim/subtask/FieldBasis.java`:

```java
package com.wastesim.subtask;

/**
 * 필드 하나의 근거 선언. 서브태스크 세트 JSON에 담기고 세트 해시가 덮는다.
 *
 * @param kind   근거의 종류
 * @param value  묻지 않고 채울 값. {@code NONE}·{@code EXPERIMENT_INTENT}면 없다
 * @param source 출처 문자열. {@code PAPER}·{@code REGULATION}·{@code MEASURED}에 필수
 * @param why    근거가 없는 이유. {@code NONE}에 필수 — 다음 사람이 왜 막혔는지 알아야 한다
 */
public record FieldBasis(BasisKind kind, Object value, String source, String why) {

    /**
     * 선언이 없는 필드. <b>{@code UNVERIFIED}가 아니라 {@code NONE}</b>이다.
     *
     * <p>선언을 빠뜨린 것을 "출처 미확인 기본값"으로 보면, 잊어버린 필드가 조용히 채워진다.
     * 선언이 없다는 것은 근거를 모른다는 뜻이므로 묻는 쪽이 맞다.
     */
    public static FieldBasis unknown() {
        return new FieldBasis(BasisKind.NONE, null, null, "근거 선언이 없다");
    }
}
```

- [ ] **Step 5: `JangnyangSubtask`에 성분을 추가한다**

`JangnyangSubtask.java`의 record 성분 목록 끝에 추가한다(Jackson이 record로 역직렬화하므로 JSON에 `basis`가 있으면 채워지고 없으면 `null`이다):

```java
        String completionCondition,
        /**
         * 이 필드의 기본값이 무엇에 근거하는가. v3까지는 선언이 없어 {@code null}이다.
         * {@link GapResolver}가 이 값을 보고 자동 채움과 되묻기를 가른다.
         */
        FieldBasis basis) {
```

- [ ] **Step 6: v4 세트 파일을 만든다**

v3를 복사해 `subtaskSetId`·`version`을 바꾸고 33문항 각각에 `basis`를 붙인다. **확인하지 않은 출처를 적지 않는다** — 코드 주석이 "논문 원본 재현"으로 묶여 있을 뿐 개별 절·표가 없는 필드는 `UNVERIFIED`다.

```bash
python - <<'PY'
import io, json, collections
src = "src/main/resources/subtask/jangnyang-simulator-v3.json"
dst = "src/main/resources/subtask/jangnyang-simulator-v4.json"
d = json.load(io.open(src, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
d["subtaskSetId"] = "jangnyang-simulator-v4"
d["version"] = 4

# 근거가 없는 셋 — 2026-09-02에 경고 표시를 붙인 값들
NONE = {
 "serviceMinutesPerSite": "현장 기록 0건. 순회 시간의 대부분이 이 값에서 나온다",
 "intraZoneTravelMinutes": "구역 간 행렬에 대각 성분이 없어 측정 대상이 아예 없다",
 "zoneAssignmentRule": "건물이 어느 구역에 있는지는 조사해야 아는 사실이다",
}
# 사용자가 정해야 하는 실험 목적
INTENT = {"simulationGoal", "scenarioType", "defaultApproval"}
# 출처를 확인한 것만 적는다
CITED = {
 "collectionSchedule": ("REGULATION", [0,1,3,4],
   "생활쓰레기 배출정보 표준데이터 경북 포항시 (배출 일·월·수·목 → 수거 +1일 해석)"),
 "dischargeWindow": ("REGULATION", [1200, 360], "포항시 북구 배출 시각 20:00~06:00"),
 "dischargeTimeMode": ("REGULATION", "PAPER_BASELINE",
   "논문 모델과 포항시 규정 두 모드. 기본은 논문 재현"),
 "trafficProfileId": ("MEASURED", "jangryang-weekday", "TMAP 24시간 288회 측정 2026-09-01"),
 "travelTimeMode": ("MEASURED", "LEGACY_CONSTANT",
   "기본은 상수 모드. OSRM_HYBRID는 지점 좌표가 없어 막혀 있다"),
}
# 그 외 기본값 — 출처 미확인
UNVERIFIED = {
 "numBuildings": 4, "residentsPerBuilding": 25, "occupationPreset": "BALANCED",
 "days": 30, "seeds": 30, "wasteMeanKg": 0.9, "wasteSigma": 0.3, "leaveSigma": 30,
 "capacity": 30.0, "threshold": 0.8, "collectionTime": 510, "collectionTimes": None,
 "truckType": "LARGE_5TON", "truckCount": 1, "initialTruckLoadKg": 0.0,
 "trafficMode": "NONE", "engine": "java", "routeTravelMinutes": 15,
 "routeAvailableCapacityKg": None, "dispatchIntervalMinutes": 0, "routeSequence": None,
 "collectionSchedule2": None,
}

for s in d["subtasks"]:
    f = s["answerField"]
    if f in NONE:
        s["basis"] = {"kind": "NONE", "why": NONE[f]}
    elif f in INTENT:
        s["basis"] = {"kind": "EXPERIMENT_INTENT"}
    elif f in CITED:
        k, v, src_ = CITED[f]
        s["basis"] = {"kind": k, "value": v, "source": src_}
    else:
        s["basis"] = {"kind": "UNVERIFIED", "value": UNVERIFIED.get(f),
                      "source": "코드 기본값 — 개별 출처 미확인"}

io.open(dst, "w", encoding="utf-8", newline="\n").write(
    json.dumps(d, ensure_ascii=False, indent=2) + "\n")
missing = [s["answerField"] for s in d["subtasks"] if s["basis"].get("kind") == "UNVERIFIED"
           and s["basis"].get("value") is None]
print("문항", len(d["subtasks"]), "| 기본값 없는 UNVERIFIED:", missing)
PY
```

출력의 `기본값 없는 UNVERIFIED` 목록이 비어 있지 않으면, 그 필드들은 `해당없음` 허용 필드다. 해당없음이 곧 "값 없이 진행 가능"이므로 `kind`를 `EXPERIMENT_INTENT`가 아니라 `UNVERIFIED` + `value: null`로 두면 Step 1의 `fillableFieldsCarryAValue`가 실패한다. **해당없음 허용 필드는 `value`를 그 필드의 "해당없음" 표현으로 채운다**(예: `routeSequence`는 빈 목록, `dispatchIntervalMinutes`는 0).

- [ ] **Step 7: 카탈로그가 v4를 읽게 한다**

`JangnyangSubtaskCatalog.java:39`의 `SET_RESOURCES`에 v4를 더한다:

```java
    private static final String[] SET_RESOURCES = {
            "/subtask/jangnyang-simulator-v2.json",
            "/subtask/jangnyang-simulator-v3.json",
            "/subtask/jangnyang-simulator-v4.json",
    };
```

- [ ] **Step 8: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=FieldBasisTest`
Expected: PASS (7 tests)

- [ ] **Step 9: 기존 테스트가 깨지지 않았는지 확인한다**

Run: `./mvnw test`
Expected: 555 tests, 0 failures

새 세트가 `latest()`가 되므로 새 세션이 v4로 시작한다. v3를 최신으로 가정한 기존 테스트가 실패할 수 있다. 실패하면 **그 테스트가 v3를 명시하도록** 고친다(`byVersion(3)`) — 세트가 늘어날 때마다 깨지지 않아야 한다.

- [ ] **Step 10: 변이로 방어를 확인한다**

```bash
# ① 선언 없는 필드를 UNVERIFIED로 취급하게 바꾼다 (FieldBasis.unknown)
#    Expected: 이 변이만으로는 실패하지 않는다 — v4가 전부 선언하므로.
#    대신 v4에서 basis 하나를 지우고 돌린다:
#    Expected: everyV4SubtaskDeclaresItsBasis 실패
# ② canFillWithoutAsking()이 항상 true를 돌려주게 바꾼다
#    Expected: onlyTwoKindsBlockAutomaticFilling 실패
# ③ v4에서 zoneAssignmentRule의 kind를 UNVERIFIED로 바꾼다
#    Expected: exactlyThreeFieldsHaveNoBasis 실패
# 각각 확인 후 되돌린다
```

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/wastesim/subtask/BasisKind.java \
        src/main/java/com/wastesim/subtask/FieldBasis.java \
        src/main/java/com/wastesim/subtask/JangnyangSubtask.java \
        src/main/java/com/wastesim/subtask/JangnyangSubtaskCatalog.java \
        src/main/resources/subtask/jangnyang-simulator-v4.json \
        src/test/java/com/wastesim/subtask/FieldBasisTest.java
git commit -m "feat(subtask): 필드별 근거를 선언해 자동 채움과 되묻기를 데이터로 가른다

근거가 코드 주석에 흩어져 있었다. wasteMeanKg=0.9가 논문에서 왔다는 사실이 공통 주석에만
있고 days·seeds는 아무 표시도 없다. 사람이 매번 '이건 물어봐야 하나'를 정하면 기준이
흔들리고, 흔들린 기준은 결국 근거 없는 값을 통과시킨다.

여섯 종류에 동작은 셋이다 — 자동으로 채운다 / 채우되 표시한다 / 반드시 묻는다.

확인하지 않은 출처를 적지 않았다. 개별 절·표가 없는 필드는 UNVERIFIED이고, 그러면 결과에
'출처 미확인' 표시가 붙는다. 보기 싫지만 사실이며, 논문을 대조해 승격시키는 만큼 줄어든다.

근거 없는 필드는 정확히 셋이다: serviceMinutesPerSite, intraZoneTravelMinutes,
zoneAssignmentRule. v3는 손대지 않고 v4를 만들었다(D-45)."
```

---

## Task 3: 미충족 필드를 근거로 가른다

**Files:**
- Create: `src/main/java/com/wastesim/subtask/GapResolver.java`
- Test: `src/test/java/com/wastesim/subtask/GapResolverTest.java`

**Interfaces:**
- Consumes: Task 2의 `BasisKind`, `FieldBasis`, `JangnyangSubtask.basis()`
- Produces:
  - `record GapResolver.Resolution(Map<String,Object> autoFilled, List<AppliedDefault> defaults, List<String> mustAsk, List<String> unverifiedFields)`
  - `static Resolution GapResolver.resolve(JangnyangSubtaskDefinition def, Set<String> answeredSubtaskIds)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/GapResolverTest.java`:

```java
package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답하지 않은 필드를 근거로 가른다.
 *
 * <p>이 클래스가 "근거 유무로 가른다"를 실제로 수행하는 곳이다. 근거 있는 값은 출처와 함께
 * 채우고, 근거 없는 값과 실험 목적은 되묻기 목록으로 내린다.
 */
class GapResolverTest {

    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    /** 아무것도 답하지 않은 상태에서 갈라 본다. */
    private static GapResolver.Resolution resolveNothing() {
        return GapResolver.resolve(v4(), Set.of());
    }

    /** 근거 없는 셋과 실험 목적은 반드시 되묻기 목록에 있어야 한다. */
    @Test
    void unbasedAndIntentFieldsGoToMustAsk() {
        GapResolver.Resolution r = resolveNothing();
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule", "simulationGoal", "scenarioType"}) {
            assertTrue(r.mustAsk().contains(f), f + "를 묻지 않으면 조용한 가정이 된다");
        }
    }

    /** 근거 없는 필드를 자동 채움에 넣으면 안 된다. */
    @Test
    void unbasedFieldsAreNeverAutoFilled() {
        GapResolver.Resolution r = resolveNothing();
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule"}) {
            assertFalse(r.autoFilled().containsKey(f),
                    f + "는 근거가 없으므로 채울 값이 없다");
        }
    }

    /** 출처가 있는 값은 채우고 근거를 함께 기록해야 한다. */
    @Test
    void citedFieldsAreFilledWithTheirSource() {
        GapResolver.Resolution r = resolveNothing();
        assertTrue(r.autoFilled().containsKey("trafficProfileId"));
        assertTrue(r.defaults().stream()
                        .anyMatch(d -> "trafficProfileId".equals(d.field())
                                && d.reason() != null && d.reason().contains("TMAP")),
                "출처 없이 채우면 다음 사람이 값의 근거를 물을 곳이 없다: " + r.defaults());
    }

    /** 출처 미확인 필드는 채우되 목록에 남아야 한다 — 결과에 표시를 붙이기 위한 것이다. */
    @Test
    void unverifiedFieldsAreFilledButListed() {
        GapResolver.Resolution r = resolveNothing();
        assertTrue(r.autoFilled().containsKey("days"), "채우지 않으면 매번 묻게 된다");
        assertTrue(r.unverifiedFields().contains("days"),
                "출처 미확인인데 표시하지 않으면 확인된 값과 구별되지 않는다");
    }

    /** 이미 답한 필드는 어느 목록에도 들어가지 않는다. */
    @Test
    void answeredFieldsAreLeftAlone() {
        JangnyangSubtaskDefinition def = v4();
        String daysId = def.subtasks().stream()
                .filter(s -> "days".equals(s.answerField()))
                .findFirst().orElseThrow().id();

        GapResolver.Resolution r = GapResolver.resolve(def, Set.of(daysId));
        assertFalse(r.autoFilled().containsKey("days"), "답한 값을 덮으면 안 된다");
        assertFalse(r.mustAsk().contains("days"));
        assertFalse(r.unverifiedFields().contains("days"));
    }

    /** 선언이 없는 필드(v3)는 전부 되묻기로 간다 — 모르는 것을 채우지 않는다. */
    @Test
    void undeclaredFieldsAllGoToMustAsk() {
        GapResolver.Resolution r = GapResolver.resolve(
                new JangnyangSubtaskCatalog().byVersion(3), Set.of());
        assertEquals(33, r.mustAsk().size(),
                "선언 없는 세트에서 무언가 자동으로 채워지면 근거 없이 채운 것이다");
        assertEquals(0, r.autoFilled().size());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=GapResolverTest`
Expected: 컴파일 실패 — `GapResolver`가 없다

- [ ] **Step 3: `GapResolver`를 만든다**

`src/main/java/com/wastesim/subtask/GapResolver.java`:

```java
package com.wastesim.subtask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 답하지 않은 필드를 <b>근거로</b> 갈라, 채울 것과 물을 것을 나눈다.
 *
 * <p>이 클래스가 "근거 유무로 가른다"를 실제로 수행하는 자리다. 사람이 매번 판단하면
 * 기준이 흔들리므로 {@link FieldBasis} 선언만 보고 기계적으로 정한다.
 *
 * <p>상태를 갖지 않는다 — 같은 입력이 같은 결과를 낸다.
 */
public final class GapResolver {

    private GapResolver() {}

    /**
     * @param autoFilled       묻지 않고 채운 값 (필드 → 값)
     * @param defaults         채운 값의 근거 기록. 결과에 함께 실린다
     * @param mustAsk          반드시 물어야 하는 필드
     * @param unverifiedFields 채웠지만 출처를 확인하지 않은 필드. 결과에 표시가 붙는다
     */
    public record Resolution(Map<String, Object> autoFilled,
                             List<AppliedDefault> defaults,
                             List<String> mustAsk,
                             List<String> unverifiedFields) {
        public Resolution {
            autoFilled = Map.copyOf(autoFilled);
            defaults = List.copyOf(defaults);
            mustAsk = List.copyOf(mustAsk);
            unverifiedFields = List.copyOf(unverifiedFields);
        }
    }

    /**
     * @param answeredSubtaskIds 이미 답이 있는 서브태스크 id. 이 필드들은 건드리지 않는다 —
     *                           답한 값을 기본값으로 덮으면 사용자 입력이 사라진다
     */
    public static Resolution resolve(JangnyangSubtaskDefinition def,
                                     Set<String> answeredSubtaskIds) {
        Map<String, Object> filled = new LinkedHashMap<>();
        List<AppliedDefault> defaults = new ArrayList<>();
        List<String> mustAsk = new ArrayList<>();
        List<String> unverified = new ArrayList<>();

        for (JangnyangSubtask s : def.subtasks()) {
            if (answeredSubtaskIds.contains(s.id())) continue;

            // 선언이 없으면 근거를 모르는 것이다. 모르는 값을 채우지 않는다.
            FieldBasis b = s.basis() != null ? s.basis() : FieldBasis.unknown();

            if (!b.kind().canFillWithoutAsking()) {
                mustAsk.add(s.answerField());
                continue;
            }
            filled.put(s.answerField(), b.value());
            defaults.add(new AppliedDefault(s.answerField(), b.value(),
                    b.source() != null ? b.source() : "출처 미확인"));
            if (b.kind().needsUnverifiedWarning()) {
                unverified.add(s.answerField());
            }
        }
        return new Resolution(filled, defaults, mustAsk, unverified);
    }
}
```

`AppliedDefault`의 생성자 성분이 `(field, value, reason)`인지 확인하고 다르면 맞춘다:

```bash
grep -nE "record AppliedDefault" -A 5 src/main/java/com/wastesim/subtask/AppliedDefault.java
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=GapResolverTest`
Expected: PASS (6 tests)

- [ ] **Step 5: 변이로 방어를 확인한다**

```bash
# ① canFillWithoutAsking() 검사를 제거해 전부 채우게 한다
#    Expected: unbasedFieldsAreNeverAutoFilled, unbasedAndIntentFieldsGoToMustAsk 실패
# ② FieldBasis.unknown()의 kind를 UNVERIFIED로 바꾼다
#    Expected: undeclaredFieldsAllGoToMustAsk 실패
# ③ unverified 목록에 추가하지 않게 한다
#    Expected: unverifiedFieldsAreFilledButListed 실패
# ④ answeredSubtaskIds 검사를 제거한다
#    Expected: answeredFieldsAreLeftAlone 실패
# 각각 확인 후 되돌린다
```

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/wastesim/subtask/GapResolver.java \
        src/test/java/com/wastesim/subtask/GapResolverTest.java
git commit -m "feat(subtask): 답하지 않은 필드를 근거로 갈라 채울 것과 물을 것을 나눈다

FieldBasis 선언만 보고 기계적으로 정한다. 사람이 매번 '이건 물어봐야 하나'를 판단하면
기준이 흔들리고, 흔들린 기준은 결국 근거 없는 값을 통과시킨다.

선언이 없는 필드는 UNVERIFIED가 아니라 NONE으로 본다 — 선언을 빠뜨린 것을 '출처 미확인
기본값'으로 취급하면 잊어버린 필드가 조용히 채워진다. 그래서 v3(선언 없음)로는 33개가
전부 되묻기로 간다.

이미 답한 필드는 건드리지 않는다. 답한 값을 기본값으로 덮으면 사용자 입력이 사라진다."
```

---

## Task 4: 출처 미확인 기본값을 결과에 표시한다

**Files:**
- Modify: `src/main/java/com/wastesim/model/DataQualityFlag.java`
- Test: `src/test/java/com/wastesim/model/UnverifiedBasisFlagTest.java`

**Interfaces:**
- Consumes: Task 3의 `Resolution.unverifiedFields()`
- Produces: `DataQualityFlag.DEFAULT_BASIS_UNVERIFIED` — `message(detail)`의 `detail`은 필드 이름을 콤마로 이은 문자열

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/model/UnverifiedBasisFlagTest.java`:

```java
package com.wastesim.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 출처를 확인하지 않은 기본값이 결과에 드러나는가.
 *
 * <p>이 표시가 없으면 확인된 값(논문 §4.2)과 확인하지 않은 값(관행상 30일)이 결과에서
 * 구별되지 않는다. 표시가 남아 있는 동안은 남은 문헌 대조 작업이 보인다.
 */
class UnverifiedBasisFlagTest {

    @Test
    void flagNamesTheFieldsAndSaysWhatItMeans() {
        String msg = DataQualityFlag.DEFAULT_BASIS_UNVERIFIED.message("days, seeds");
        assertTrue(msg.contains("days, seeds"), "어느 필드인지 알 수 없으면 확인할 수 없다: " + msg);
        assertTrue(msg.contains("출처"), msg);
    }

    /** 결과가 이 표시를 실으면 운영 예측이 아니게 된다. */
    @Test
    void resultWithUnverifiedBasisIsNotOperational() {
        SimulationResult r = new SimulationResult("08:30", 0, java.util.Map.of(),
                java.util.Map.of(), 0.0, 1);
        r.setCoordinateQuality(CoordinateQuality.MEASURED_SITE);
        assertFalse(r.isNotForOperationalUse(), "지점 실측 좌표에 가정이 없으면 운영 후보다");

        r.addDataQualityFlag(DataQualityFlag.DEFAULT_BASIS_UNVERIFIED, "days");
        assertTrue(r.isNotForOperationalUse(),
                "출처를 확인하지 않은 기본값으로 낸 값을 운영 예측이라 부를 수 없다");
    }
}
```

`SimulationResult`의 생성자 성분을 확인하고 맞춘다:

```bash
grep -nE "public SimulationResult\(" -A 8 src/main/java/com/wastesim/model/SimulationResult.java
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=UnverifiedBasisFlagTest`
Expected: 컴파일 실패 — `DEFAULT_BASIS_UNVERIFIED`가 없다

- [ ] **Step 3: 표시를 추가한다**

`DataQualityFlag.java`의 `ZONE_ASSIGNMENT_ASSUMED` 뒤에 더한다(마지막 항목의 `;`를 `,`로 바꾼다):

```java
    /**
     * 기본값을 썼는데 <b>그 값의 출처를 확인하지 않았다.</b>
     *
     * <p>이 표시가 없으면 확인된 값(논문 §4.2 표 3)과 확인하지 않은 값(관행상 30일)이
     * 결과에서 구별되지 않는다. 표시가 남아 있는 동안은 남은 문헌 대조 작업이 보인다 —
     * 승격시키는 만큼 줄어드는 것이 이 표시의 설계 의도다.
     */
    DEFAULT_BASIS_UNVERIFIED(
            "다음 값을 기본값으로 채웠는데 출처를 확인하지 않았습니다: %s. "
                    + "논문·규정에서 온 값과 관행으로 굳은 값이 섞여 있을 수 있습니다.");
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=UnverifiedBasisFlagTest`
Expected: PASS (2 tests)

- [ ] **Step 5: 기존 테스트 확인**

Run: `./mvnw test`
Expected: 563 tests, 0 failures

- [ ] **Step 6: 변이로 방어를 확인한다**

```bash
# ① 문구에서 %s를 지운다 (필드 이름이 사라진다)
#    Expected: flagNamesTheFieldsAndSaysWhatItMeans 실패
# ② SimulationResult.isNotForOperationalUse()가 dataQualityFlags를 보지 않게 한다
#    Expected: resultWithUnverifiedBasisIsNotOperational 실패
# 확인 후 되돌린다
```

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/wastesim/model/DataQualityFlag.java \
        src/test/java/com/wastesim/model/UnverifiedBasisFlagTest.java
git commit -m "feat(model): 출처를 확인하지 않은 기본값을 결과에 표시한다

이 표시가 없으면 확인된 값(논문 §4.2)과 확인하지 않은 값(관행상 30일)이 결과에서
구별되지 않는다. 표시가 남아 있는 동안은 남은 문헌 대조 작업이 보이고, 승격시키는 만큼
줄어든다 — 남은 일이 결과에 드러나는 구조다.

어느 필드인지 문구에 담는다. 이름이 없으면 무엇을 확인해야 하는지 알 수 없다."
```

---

## Task 5: 인용 조각을 검사해 지어낸 값을 버린다

LLM에게 값만 내라고 하면 요청에 없는 것도 만들어 낸다. 값마다 **근거가 된 원문 조각**을 함께 내게 하고, 그 조각이 실제 요청에 있는지 결정적으로 검사한다. "한 달 → 30일" 같은 정규화는 통과하고 순수한 창작은 걸린다.

**Files:**
- Create: `src/main/java/com/wastesim/llm/ExtractedValue.java`
- Create: `src/main/java/com/wastesim/llm/RequestExtraction.java`
- Create: `src/main/java/com/wastesim/llm/SpanVerifier.java`
- Test: `src/test/java/com/wastesim/llm/SpanVerifierTest.java`

**Interfaces:**
- Produces:
  - `record ExtractedValue(String field, Object value, String span)`
  - `record RequestExtraction(List<ExtractedValue> values, String targetRegion, String targetDomain, String requestedConclusion)`
  - `record SpanVerifier.Verified(List<ExtractedValue> accepted, List<ExtractedValue> rejected)`
  - `static Verified SpanVerifier.verify(String request, RequestExtraction extraction)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/llm/SpanVerifierTest.java`:

```java
package com.wastesim.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM이 지어낸 값을 버린다.
 *
 * <p>값만 내라고 하면 요청에 없는 것도 만들어 낸다. 그래서 값마다 근거가 된 원문 조각을
 * 함께 내게 하고, 그 조각이 실제 요청에 있는지 검사한다. 한 줄짜리 검사인데 이것이
 * "근거 없는 값이 사실처럼 흘러드는" 문제를 막는 자리다.
 */
class SpanVerifierTest {

    private static RequestExtraction ex(ExtractedValue... vs) {
        return new RequestExtraction(List.of(vs), null, null, null);
    }

    /** 요청에 있는 조각을 인용한 값은 채택한다. */
    @Test
    void acceptsValuesQuotingTheRequest() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 26개 동으로 돌려줘",
                ex(new ExtractedValue("numBuildings", 26, "26개 동")));
        assertEquals(1, v.accepted().size());
        assertEquals(0, v.rejected().size());
    }

    /** 정규화는 정당하다 — "한 달"에서 30을 뽑는 것은 창작이 아니다. */
    @Test
    void allowsNormalizationWhenTheSpanIsPresent() {
        SpanVerifier.Verified v = SpanVerifier.verify("한 달 돌려줘",
                ex(new ExtractedValue("days", 30, "한 달")));
        assertEquals(1, v.accepted().size(), "정규화를 막으면 자연어 해석이 불가능해진다");
    }

    /** <b>이 테스트가 이 클래스의 요점이다.</b> 요청에 없는 조각을 댄 값은 버린다. */
    @Test
    void rejectsValuesWhoseSpanIsNotInTheRequest() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 시뮬레이터 만들어 줘",
                ex(new ExtractedValue("seeds", 10, "10회 반복")));
        assertEquals(0, v.accepted().size(), "요청에 없는 근거를 댄 값을 받으면 안 된다");
        assertEquals(1, v.rejected().size());
        assertEquals("seeds", v.rejected().get(0).field());
    }

    /** 조각이 비어 있거나 없는 값도 버린다 — 근거를 대지 않은 것이다. */
    @Test
    void rejectsValuesWithNoSpanAtAll() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 시뮬레이터",
                ex(new ExtractedValue("days", 30, null),
                   new ExtractedValue("seeds", 10, "   ")));
        assertEquals(0, v.accepted().size());
        assertEquals(2, v.rejected().size());
    }

    /** 공백 차이는 용인한다 — LLM이 조각을 옮길 때 공백이 흔히 달라진다. */
    @Test
    void toleratesWhitespaceDifferences() {
        SpanVerifier.Verified v = SpanVerifier.verify("26개  동으로",
                ex(new ExtractedValue("numBuildings", 26, "26개 동")));
        assertEquals(1, v.accepted().size(),
                "공백 하나로 정당한 인용을 버리면 쓸 수 없는 검사가 된다");
    }

    /** 대소문자 차이도 용인한다. */
    @Test
    void toleratesCaseDifferences() {
        SpanVerifier.Verified v = SpanVerifier.verify("ROUND_ROBIN으로 배정해",
                ex(new ExtractedValue("zoneAssignmentRule", "ROUND_ROBIN", "round_robin")));
        assertEquals(1, v.accepted().size());
    }

    /** 요청이 비어 있으면 아무 값도 채택할 수 없다. */
    @Test
    void emptyRequestAcceptsNothing() {
        SpanVerifier.Verified v = SpanVerifier.verify("",
                ex(new ExtractedValue("days", 30, "한 달")));
        assertEquals(0, v.accepted().size());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=SpanVerifierTest`
Expected: 컴파일 실패 — `SpanVerifier`가 없다

- [ ] **Step 3: 세 record를 만든다**

`src/main/java/com/wastesim/llm/ExtractedValue.java`:

```java
package com.wastesim.llm;

/**
 * LLM이 요청에서 뽑아낸 값 하나.
 *
 * @param field 설계도의 답변 필드 이름
 * @param value 뽑은 값
 * @param span  <b>근거가 된 원문 조각.</b> 요청 문장에서 그대로 가져온 것이어야 한다 —
 *              {@link SpanVerifier}가 실제로 있는지 검사하고 없으면 값을 버린다
 */
public record ExtractedValue(String field, Object value, String span) {}
```

`src/main/java/com/wastesim/llm/RequestExtraction.java`:

```java
package com.wastesim.llm;

import java.util.List;

/**
 * 요청 하나에서 뽑아낸 전부.
 *
 * <p>설계도 값({@code values})과 <b>판정용 필드</b>가 갈라져 있다. 판정용 필드는 설계도에
 * 값으로 들어가지 않고 {@link FeasibilityGate}만 본다 — 거부 판정에 필요한 것이 33개 답변
 * 필드에 없기 때문이다.
 *
 * @param targetRegion        요청이 가리키는 지역. 비어 있으면 장량동으로 본다 —
 *                            "시뮬레이터 만들어 줘"처럼 지역을 생략한 요청이 정상이므로,
 *                            침묵을 거부 근거로 쓰지 않는다
 * @param targetDomain        요청의 도메인. 비어 있으면 생활쓰레기 수거로 본다
 * @param requestedConclusion 요청이 원하는 결론. 비어 있으면 판정하지 않고 통과시킨다
 */
public record RequestExtraction(List<ExtractedValue> values,
                                String targetRegion,
                                String targetDomain,
                                String requestedConclusion) {
    public RequestExtraction {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
```

- [ ] **Step 4: `SpanVerifier`를 만든다**

`src/main/java/com/wastesim/llm/SpanVerifier.java`:

```java
package com.wastesim.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 뽑은 값이 <b>요청에 실제로 근거하는가</b>를 검사한다.
 *
 * <p>LLM에게 값만 내라고 하면 요청에 없는 것도 만들어 낸다. 그래서 값마다 근거가 된 원문
 * 조각을 함께 내게 하고, 그 조각이 요청 문자열에 있는지 본다. 한 줄짜리 검사이지만
 * "근거 없는 값이 사실처럼 흘러드는" 문제를 막는 자리다.
 *
 * <p><b>정규화는 막지 않는다.</b> "한 달"에서 30을 뽑는 것은 창작이 아니라 해석이며, 조각
 * ("한 달")이 요청에 있으므로 통과한다. 걸리는 것은 조각 자체가 없는 경우다.
 *
 * <p>공백과 대소문자 차이는 용인한다 — LLM이 조각을 옮길 때 흔히 달라지고, 그것으로 정당한
 * 인용을 버리면 쓸 수 없는 검사가 된다.
 */
public final class SpanVerifier {

    private SpanVerifier() {}

    /**
     * @param accepted 인용이 확인된 값. 이것만 세션에 제출한다
     * @param rejected 인용을 확인하지 못한 값. 되묻기 대상으로 내린다
     */
    public record Verified(List<ExtractedValue> accepted, List<ExtractedValue> rejected) {
        public Verified {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }
    }

    public static Verified verify(String request, RequestExtraction extraction) {
        List<ExtractedValue> accepted = new ArrayList<>();
        List<ExtractedValue> rejected = new ArrayList<>();
        String haystack = normalize(request);

        for (ExtractedValue v : extraction.values()) {
            String needle = normalize(v.span());
            if (needle.isEmpty() || haystack.isEmpty() || !haystack.contains(needle)) {
                rejected.add(v);
            } else {
                accepted.add(v);
            }
        }
        return new Verified(accepted, rejected);
    }

    /** 공백을 없애고 소문자로 맞춘다. {@code null}은 빈 문자열로 본다. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=SpanVerifierTest`
Expected: PASS (7 tests)

- [ ] **Step 6: 변이로 방어를 확인한다**

```bash
# ① contains 검사를 없애고 전부 accepted에 넣는다
#    Expected: rejectsValuesWhoseSpanIsNotInTheRequest, rejectsValuesWithNoSpanAtAll 실패
# ② needle.isEmpty() 검사를 제거한다
#    Expected: rejectsValuesWithNoSpanAtAll 실패 (빈 조각이 통과한다)
# ③ normalize에서 공백 제거를 없앤다
#    Expected: toleratesWhitespaceDifferences 실패
# ④ normalize에서 소문자화를 없앤다
#    Expected: toleratesCaseDifferences 실패
# 각각 확인 후 되돌린다
```

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/wastesim/llm/ src/test/java/com/wastesim/llm/
git commit -m "feat(llm): 인용 조각을 검사해 LLM이 지어낸 값을 버린다

값만 내라고 하면 요청에 없는 것도 만들어 낸다. 값마다 근거가 된 원문 조각을 함께 내게
하고 그 조각이 요청에 있는지 검사한다 — 한 줄짜리 검사인데 이것이 근거 없는 값이 사실처럼
흘러드는 문제를 막는 자리다.

정규화는 막지 않는다. '한 달'에서 30을 뽑는 것은 창작이 아니라 해석이고, 조각이 요청에
있으므로 통과한다. 걸리는 것은 조각 자체가 없는 경우다.

공백과 대소문자 차이는 용인한다. LLM이 조각을 옮길 때 흔히 달라지고, 그것으로 정당한
인용을 버리면 쓸 수 없는 검사가 된다.

판정용 필드(targetRegion 등)를 설계도 값과 갈라 뒀다 — 거부 판정에 필요한 것이 33개
답변 필드에 없다."
```

---

## Task 6: 만들 수 없는 요청을 무엇이 필요한지와 함께 거부한다

거부는 "안 됩니다"에서 끝나지 않고 무엇이 있으면 되는지를 담는다. 통째로 막으면 사용자가 우회로가 있다는 것을 모른다.

**Files:**
- Create: `src/main/java/com/wastesim/llm/FeasibilityVerdict.java`
- Create: `src/main/java/com/wastesim/llm/FeasibilityGate.java`
- Test: `src/test/java/com/wastesim/llm/FeasibilityGateTest.java`

**Interfaces:**
- Consumes: Task 5의 `RequestExtraction`
- Produces:
  - `enum FeasibilityVerdict.Reason { OUT_OF_REGION, AXIS_NOT_IN_MODEL, DATA_UNAVAILABLE, NOT_A_SIMULATION }`
  - `record FeasibilityVerdict.Missing(String item, boolean obtainable, String note)`
  - `record FeasibilityVerdict(boolean feasible, Reason reason, String message, List<Missing> whatWouldBeNeeded)`
    - `static FeasibilityVerdict ok()`
  - `static FeasibilityVerdict FeasibilityGate.judge(RequestExtraction extraction)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/llm/FeasibilityGateTest.java`:

```java
package com.wastesim.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 만들 수 없는 요청을 거부한다.
 *
 * <p>판정은 <b>LLM이 아니라 코드</b>가 한다. LLM은 "부산"이라는 단어를 뽑을 뿐이고
 * "부산은 지원하지 않는다"는 여기서 정한다 — 그래야 거부 동작을 테스트로 고정할 수 있다.
 *
 * <p>거부는 "안 됩니다"에서 끝나지 않는다. 통째로 막으면 사용자가 우회로가 있다는 것을
 * 모른다.
 */
class FeasibilityGateTest {

    private static RequestExtraction req(String region, String domain, String conclusion) {
        return new RequestExtraction(List.of(), region, domain, conclusion);
    }

    /** 다른 지역은 거부한다. */
    @Test
    void rejectsOtherRegions() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("부산", null, null));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.OUT_OF_REGION, v.reason());
    }

    /**
     * <b>지역이 비었을 때는 거부하지 않는다.</b> "시뮬레이터 만들어 줘"처럼 지역을 생략한
     * 요청이 정상이므로, 침묵을 거부 근거로 쓰지 않는다.
     */
    @Test
    void silenceAboutRegionIsNotGroundsForRefusal() {
        assertTrue(FeasibilityGate.judge(req(null, null, null)).feasible());
        assertTrue(FeasibilityGate.judge(req("", null, null)).feasible());
    }

    /** 장량동·포항은 통과한다. */
    @Test
    void acceptsTheSupportedRegion() {
        assertTrue(FeasibilityGate.judge(req("장량동", null, null)).feasible());
        assertTrue(FeasibilityGate.judge(req("포항시 북구 장량동", null, null)).feasible());
    }

    /** 모델에 없는 축은 거부한다. */
    @Test
    void rejectsAxesNotInTheModel() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "종량제 봉투 가격을 올리면 배출량이 줄어드는가"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.AXIS_NOT_IN_MODEL, v.reason());
    }

    /** 지점 단위 결론은 데이터가 없어 거부하되 구역 단위 대안을 안내한다. */
    @Test
    void rejectsSiteLevelConclusionsButOffersTheZoneAlternative() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "수거 지점 단위 최적 경로를 찾아줘"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.DATA_UNAVAILABLE, v.reason());
        assertTrue(v.whatWouldBeNeeded().stream()
                        .anyMatch(m -> m.note() != null && m.note().contains("ZONE_PROXY_HYBRID")),
                "통째로 막으면 사용자가 우회로가 있다는 것을 모른다: " + v.whatWouldBeNeeded());
    }

    /** 조회성 요청은 시뮬레이션이 아니다. */
    @Test
    void rejectsLookupRequests() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "장량동 쓰레기 배출량 알려줘"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.NOT_A_SIMULATION, v.reason());
    }

    /** <b>모든 거부는 부족한 것 목록을 함께 낸다.</b> */
    @Test
    void everyRefusalCarriesWhatWouldBeNeeded() {
        List<RequestExtraction> refused = List.of(
                req("부산", null, null),
                req(null, null, "종량제 봉투 가격을 올리면"),
                req(null, null, "수거 지점 단위 최적 경로"),
                req(null, null, "장량동 배출량 알려줘"));

        for (RequestExtraction r : refused) {
            FeasibilityVerdict v = FeasibilityGate.judge(r);
            assertFalse(v.feasible());
            assertFalse(v.whatWouldBeNeeded().isEmpty(),
                    "'안 됩니다'로 끝나는 거부는 사용자가 다음에 무엇을 할지 모른다: " + v.reason());
            assertNotNull(v.message());
        }
    }

    /** 지역 거부 목록은 자동 수집 가능한 것과 아닌 것을 갈라 준다. */
    @Test
    void regionRefusalSeparatesObtainableFromNot() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("부산", null, null));
        assertTrue(v.whatWouldBeNeeded().stream().anyMatch(FeasibilityVerdict.Missing::obtainable),
                "자동 수집 가능한 항목이 있어야 다음 작업의 재료가 된다");
        assertTrue(v.whatWouldBeNeeded().stream().anyMatch(m -> !m.obtainable()),
                "사람이 채워야 하는 항목을 숨기면 자동으로 될 것처럼 읽힌다");
    }

    /** 통과한 판정에는 거부 사유가 없다. */
    @Test
    void feasibleVerdictHasNoReason() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("장량동", null, null));
        assertTrue(v.feasible());
        assertNull(v.reason());
        assertEquals(List.of(), v.whatWouldBeNeeded());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=FeasibilityGateTest`
Expected: 컴파일 실패 — `FeasibilityGate`, `FeasibilityVerdict`가 없다

- [ ] **Step 3: `FeasibilityVerdict`를 만든다**

`src/main/java/com/wastesim/llm/FeasibilityVerdict.java`:

```java
package com.wastesim.llm;

import java.util.List;

/**
 * 이 요청으로 시뮬레이터를 만들 수 있는가, 없으면 무엇이 필요한가.
 *
 * @param whatWouldBeNeeded 부족한 것. <b>거부라면 비어 있을 수 없다</b> — "안 됩니다"로
 *                          끝나는 거부는 사용자가 다음에 무엇을 할지 모른다
 */
public record FeasibilityVerdict(boolean feasible, Reason reason, String message,
                                 List<Missing> whatWouldBeNeeded) {

    public FeasibilityVerdict {
        whatWouldBeNeeded = whatWouldBeNeeded == null ? List.of()
                                                      : List.copyOf(whatWouldBeNeeded);
    }

    public enum Reason {
        /** 이 시스템은 장량동만 다룬다. 구역 정의와 주민 모델이 지역에 묶여 있다 */
        OUT_OF_REGION,
        /** 요청이 가리키는 변수가 DEVS 모델에 없다(가격·분리율 등) */
        AXIS_NOT_IN_MODEL,
        /** 결론에 필요한 데이터가 없다(지점 좌표 0곳) */
        DATA_UNAVAILABLE,
        /** 실행할 시뮬레이션이 아니라 사실 조회다 */
        NOT_A_SIMULATION
    }

    /**
     * @param obtainable API로 자동 수집할 수 있는가. 사람이 채워야 하는 것을 숨기면
     *                   자동으로 될 것처럼 읽힌다
     */
    public record Missing(String item, boolean obtainable, String note) {}

    public static FeasibilityVerdict ok() {
        return new FeasibilityVerdict(true, null, null, List.of());
    }
}
```

- [ ] **Step 4: `FeasibilityGate`를 만든다**

`src/main/java/com/wastesim/llm/FeasibilityGate.java`:

```java
package com.wastesim.llm;

import java.util.List;
import java.util.Locale;

/**
 * 만들 수 없는 요청을 거부한다. <b>LLM이 아니라 코드가 판정한다.</b>
 *
 * <p>LLM은 "부산"이라는 단어를 뽑을 뿐이고 "부산은 지원하지 않는다"는 여기서 정한다.
 * 판정을 LLM에 맡기면 거부 동작을 테스트로 고정할 수 없다.
 *
 * <p>거부는 사유와 <b>부족한 것 목록</b>을 함께 낸다. 통째로 막으면 사용자가 우회로가
 * 있다는 것을 모른다 — 지점 단위 경로는 막히지만 구역 단위로는 지금도 돌아간다.
 */
public final class FeasibilityGate {

    private FeasibilityGate() {}

    /** 이 시스템이 다루는 지역. 구역 정의와 주민 모델이 여기에 묶여 있다. */
    private static final List<String> SUPPORTED_REGION = List.of("장량", "포항");

    /** 모델에 없는 변수를 가리키는 말. 있으면 그 결론은 낼 수 없다. */
    private static final List<String> ABSENT_AXES =
            List.of("가격", "요금", "봉투값", "분리율", "재활용률", "보조금", "과태료");

    /** 지점 단위 결론을 가리키는 말. 수거 지점 좌표가 0곳이라 답할 수 없다. */
    private static final List<String> SITE_LEVEL =
            List.of("지점 단위", "지점단위", "지점별 경로", "최적 경로", "최단 경로");

    /** 실행이 아니라 조회를 가리키는 말. */
    private static final List<String> LOOKUP =
            List.of("알려줘", "얼마야", "몇이야", "연락처", "조회");

    public static FeasibilityVerdict judge(RequestExtraction extraction) {
        String region = lower(extraction.targetRegion());
        // 지역이 비어 있으면 거부하지 않는다 — "시뮬레이터 만들어 줘"처럼 생략한 요청이
        // 정상이다. 침묵을 거부 근거로 쓰지 않는다.
        if (!region.isEmpty() && SUPPORTED_REGION.stream().noneMatch(region::contains)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.OUT_OF_REGION,
                    "이 시뮬레이터는 포항시 북구 장량동만 다룹니다.", regionNeeds());
        }

        String conclusion = lower(extraction.requestedConclusion());
        if (containsAny(conclusion, ABSENT_AXES)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.AXIS_NOT_IN_MODEL,
                    "이 모델에는 가격·분리율 같은 변수가 없습니다.",
                    List.of(new FeasibilityVerdict.Missing(
                            "요청한 변수를 담은 모델", false,
                            "DEVS 모델은 배출량·수거 일정·차량·교통만 다룹니다. 배출량 변화로 "
                                    + "근사할 수는 있지만 그것은 다른 질문입니다.")));
        }
        if (containsAny(conclusion, SITE_LEVEL)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.DATA_UNAVAILABLE,
                    "수거 지점 좌표가 0곳이라 지점 단위 결론은 낼 수 없습니다.",
                    List.of(
                        new FeasibilityVerdict.Missing("수거 지점 좌표", false,
                                "현장 GPS 또는 주소 지오코딩이 필요합니다."),
                        new FeasibilityVerdict.Missing("구역 단위 대안", true,
                                "ZONE_PROXY_HYBRID로 교통 구역 사이는 지금도 계산됩니다. "
                                        + "같은 구역 안의 방문 순서는 결과에 반영되지 않습니다.")));
        }
        if (containsAny(conclusion, LOOKUP)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.NOT_A_SIMULATION,
                    "이것은 실행할 시뮬레이션이 아니라 사실 조회입니다.",
                    List.of(new FeasibilityVerdict.Missing("조회 경로", false,
                            "시뮬레이터는 조건을 바꿔 결과를 비교하는 도구입니다. 값 자체는 "
                                    + "공공데이터나 담당 부서에서 확인해야 합니다.")));
        }
        return FeasibilityVerdict.ok();
    }

    /**
     * 다른 지역에 필요한 것. {@code obtainable: true} 넷이 지역 온보딩 작업의 재료 목록이다.
     */
    private static List<FeasibilityVerdict.Missing> regionNeeds() {
        return List.of(
            new FeasibilityVerdict.Missing("교통 구역 정의", false,
                    "장량동 A~D는 교통량 CSV 링크 매핑과 랜드마크로 정했습니다. 대상 지역에 "
                            + "그 자료가 없으면 사람이 구역을 정해야 합니다."),
            new FeasibilityVerdict.Missing("주민 배출 모델", false,
                    "0.9kg/인·일과 직업별 외출·귀가 시각은 원룸촌 논문 모델입니다. 주거 "
                            + "형태가 다르면 맞지 않습니다."),
            new FeasibilityVerdict.Missing("도로 자유주행시간", true,
                    "OSRM — 구역이 정해지면 자동 수집"),
            new FeasibilityVerdict.Missing("시간대 혼잡", true,
                    "TMAP — 프로파일 1개당 288회 호출"),
            new FeasibilityVerdict.Missing("수거 일정·미수거일", true,
                    "생활쓰레기 배출정보 표준데이터(시군구별, 채움 편차 있음)"),
            new FeasibilityVerdict.Missing("인구·세대수", true, "주민등록 통계"));
    }

    private static boolean containsAny(String text, List<String> needles) {
        return !text.isEmpty() && needles.stream().anyMatch(text::contains);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=FeasibilityGateTest`
Expected: PASS (9 tests)

- [ ] **Step 6: 변이로 방어를 확인한다**

```bash
# ① 지역 검사를 없앤다 (항상 통과)
#    Expected: rejectsOtherRegions 실패
# ② region.isEmpty() 검사를 없애 빈 지역도 거부하게 한다
#    Expected: silenceAboutRegionIsNotGroundsForRefusal 실패
# ③ SITE_LEVEL 거부에서 구역 단위 대안 항목을 지운다
#    Expected: rejectsSiteLevelConclusionsButOffersTheZoneAlternative 실패
# ④ 모든 거부의 whatWouldBeNeeded를 List.of()로 바꾼다
#    Expected: everyRefusalCarriesWhatWouldBeNeeded 실패
# ⑤ regionNeeds()에서 obtainable=true 항목을 모두 지운다
#    Expected: regionRefusalSeparatesObtainableFromNot 실패
# 각각 확인 후 되돌린다
```

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/wastesim/llm/FeasibilityGate.java \
        src/main/java/com/wastesim/llm/FeasibilityVerdict.java \
        src/test/java/com/wastesim/llm/FeasibilityGateTest.java
git commit -m "feat(llm): 만들 수 없는 요청을 무엇이 필요한지와 함께 거부한다

판정은 LLM이 아니라 코드가 한다. LLM은 '부산'이라는 단어를 뽑을 뿐이고 '부산은 지원하지
않는다'는 여기서 정한다 — 판정을 LLM에 맡기면 거부 동작을 테스트로 고정할 수 없다.

거부는 '안 됩니다'에서 끝나지 않고 부족한 것 목록을 함께 낸다. 통째로 막으면 사용자가
우회로가 있다는 것을 모른다 — 지점 단위 경로는 막히지만 구역 단위로는 지금도 돌아간다.

지역이 비었을 때는 거부하지 않는다. '시뮬레이터 만들어 줘'처럼 생략한 요청이 정상이므로
침묵을 거부 근거로 쓰지 않는다.

지역 거부 목록은 자동 수집 가능한 것과 사람이 채워야 하는 것을 갈라 준다. 후자를 숨기면
자동으로 될 것처럼 읽히고, 전자는 지역 온보딩 작업의 재료 목록이 된다."
```

---

## Task 7: LLM 구현체와 폴백을 붙여 흐름을 완성한다

**Files:**
- Create: `src/main/java/com/wastesim/llm/RequestInterpreter.java`
- Create: `src/main/java/com/wastesim/llm/OpenAiRequestInterpreter.java`
- Create: `src/main/java/com/wastesim/llm/BlueprintComposer.java`
- Test: `src/test/java/com/wastesim/llm/BlueprintComposerTest.java`

**Interfaces:**
- Consumes: Task 1 `submit(..., SubtaskAnswerSource)`, Task 3 `GapResolver.resolve`, Task 5 `SpanVerifier.verify`, Task 6 `FeasibilityGate.judge`
- Produces:
  - `interface RequestInterpreter { RequestExtraction extract(String request, List<String> answerFields) throws InterpreterException; }`
  - `class InterpreterException extends Exception`
  - `record BlueprintComposer.Outcome(FeasibilityVerdict verdict, List<String> mustAsk, boolean usedFallback, String fallbackNotice)`
  - `BlueprintComposer.Outcome compose(String sessionKey, String request)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/llm/BlueprintComposerTest.java`:

```java
package com.wastesim.llm;

import com.wastesim.subtask.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요청 하나가 설계도로 가는 전체 흐름.
 *
 * <p>LLM을 실제로 부르지 않는다. {@link RequestInterpreter}를 스텁으로 두고 흐름만
 * 검증한다 — TMAP·OSRM에 쓴 방식과 같다. 실제 호출은 별도 통합 확인으로 분리한다.
 */
class BlueprintComposerTest {

    private static SubtaskSessionService sessions() {
        return new SubtaskSessionService(new JangnyangSubtaskCatalog(),
                new InMemorySubtaskSessionStore(), new JangnyangSubtaskValidator(),
                new JangnyangCompletenessChecker());
    }

    /** 고정 응답을 내는 스텁. */
    private static RequestInterpreter stub(RequestExtraction fixed) {
        return (request, fields) -> fixed;
    }

    /** 항상 실패하는 스텁 — 서비스 장애를 흉내낸다. */
    private static RequestInterpreter failing() {
        return (request, fields) -> { throw new InterpreterException("서비스 없음"); };
    }

    /** 거부 사유가 있으면 세션을 만들지 않고 끝낸다. */
    @Test
    void refusedRequestDoesNotStartASession() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc,
                stub(new RequestExtraction(List.of(), "부산", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s1", "부산 시뮬레이터 만들어 줘");

        assertFalse(o.verdict().feasible());
        assertEquals(FeasibilityVerdict.Reason.OUT_OF_REGION, o.verdict().reason());
        assertNull(svc.activeSession("s1"), "거부한 요청으로 세션을 만들면 안 된다");
    }

    /** 인용이 확인된 값만 세션에 들어가고, 출처가 LLM_NORMALIZED로 남는다. */
    @Test
    void onlyVerifiedValuesEnterTheSessionAndAreMarkedAsLlm() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue("numBuildings", 26, "26개 동"),
                        new ExtractedValue("seeds", 10, "10회 반복")),   // 요청에 없다
                        "장량동", null, null)));

        composer.compose("s2", "장량동 26개 동으로 돌려줘");

        JangnyangSubtaskSession session = svc.activeSession("s2");
        assertNotNull(session);
        boolean anyLlm = session.answers().values().stream()
                .anyMatch(a -> a.source() == SubtaskAnswerSource.LLM_NORMALIZED);
        assertTrue(anyLlm, "LLM이 채운 값이 원장에 그 출처로 남아야 한다");
    }

    /** 인용을 확인하지 못한 필드는 되묻기 목록에 있어야 한다. */
    @Test
    void unverifiedValuesGoToMustAsk() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue("seeds", 10, "10회 반복")),   // 요청에 없다
                        "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s3", "장량동 시뮬레이터");
        assertTrue(o.mustAsk().contains("seeds"),
                "지어낸 값을 버렸으면 그 필드를 물어야 한다: " + o.mustAsk());
    }

    /** 근거 없는 셋은 언제나 되묻기 목록에 있다. */
    @Test
    void unbasedFieldsAreAlwaysAsked() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s4", "장량동 시뮬레이터 만들어 줘");
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule"}) {
            assertTrue(o.mustAsk().contains(f), f + "를 묻지 않으면 조용한 가정이 된다");
        }
    }

    /**
     * <b>LLM이 죽으면 기본값으로 채우지 않는다.</b> 문항 흐름으로 넘기고 사용자에게 알린다.
     */
    @Test
    void llmFailureFallsBackLoudlyNotSilently() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, failing());

        BlueprintComposer.Outcome o = composer.compose("s5", "장량동 시뮬레이터 만들어 줘");

        assertTrue(o.verdict().feasible(), "LLM 장애는 요청이 불가능하다는 뜻이 아니다");
        assertTrue(o.usedFallback());
        assertNotNull(o.fallbackNotice(), "조용히 문항으로 넘기면 사용자가 이유를 모른다");
        assertNotNull(svc.activeSession("s5"), "폴백은 문항 흐름으로 진행하는 것이다");
        assertTrue(svc.activeSession("s5").answers().isEmpty(),
                "LLM이 죽었는데 값이 채워져 있으면 어디서 온 값인지 알 수 없다");
    }

    /** 스키마가 깨진 추출은 전체를 버린다 — 부분 파싱 금지. */
    @Test
    void malformedExtractionIsDiscardedWholesale() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue(null, 26, "26개 동")),   // 필드 이름이 없다
                        "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s6", "장량동 26개 동");
        assertTrue(o.usedFallback(), "반쯤 읽은 결과를 쓰면 무엇이 빠졌는지 알 수 없다");
        assertTrue(svc.activeSession("s6").answers().isEmpty());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=BlueprintComposerTest`
Expected: 컴파일 실패 — `RequestInterpreter`, `BlueprintComposer`가 없다

- [ ] **Step 3: 인터페이스와 예외를 만든다**

`src/main/java/com/wastesim/llm/RequestInterpreter.java`:

```java
package com.wastesim.llm;

import java.util.List;

/**
 * 자유 문장에서 설계도 값을 뽑는다. <b>판정하지 않는다.</b>
 *
 * <p>인터페이스로 둔 이유는 테스트에서 LLM을 부르지 않기 위한 것이다. 고정 응답 스텁으로
 * 흐름을 검증하고 실제 호출은 별도 통합 확인으로 분리한다 — TMAP·OSRM에 쓴 방식과 같다.
 */
@FunctionalInterface
public interface RequestInterpreter {

    /**
     * @param answerFields 뽑을 수 있는 필드 이름 목록. 이 밖의 필드를 내면 호출부가 버린다
     * @throws InterpreterException 서비스 장애·형식 오류. 호출부는 문항 흐름으로 넘긴다
     */
    RequestExtraction extract(String request, List<String> answerFields)
            throws InterpreterException;
}
```

`src/main/java/com/wastesim/llm/InterpreterException.java`:

```java
package com.wastesim.llm;

/** 추출에 실패했다. 조용히 기본값으로 채우지 않고 문항 흐름으로 넘기기 위한 신호다. */
public class InterpreterException extends Exception {
    public InterpreterException(String message) { super(message); }
    public InterpreterException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: `BlueprintComposer`를 만든다**

`src/main/java/com/wastesim/llm/BlueprintComposer.java`:

```java
package com.wastesim.llm;

import com.wastesim.subtask.GapResolver;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.SubtaskAnswerSource;
import com.wastesim.subtask.SubtaskSessionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 요청 하나를 설계도로 옮긴다 — 이 흐름의 조립 지점.
 *
 * <p>순서가 설계의 전부다: 뽑고(LLM) → 판정하고(코드) → 인용을 확인하고(코드) →
 * 기존 검증기에 넘기고 → 남은 것을 근거로 가른다.
 */
@Service
public class BlueprintComposer {

    private final SubtaskSessionService sessions;
    private final RequestInterpreter interpreter;

    public BlueprintComposer(SubtaskSessionService sessions, RequestInterpreter interpreter) {
        this.sessions = sessions;
        this.interpreter = interpreter;
    }

    /**
     * @param usedFallback    LLM 추출을 쓰지 못해 문항 흐름으로 갔는가
     * @param fallbackNotice  사용자에게 알릴 문구. 조용히 넘기면 이유를 알 수 없다
     */
    public record Outcome(FeasibilityVerdict verdict, List<String> mustAsk,
                          boolean usedFallback, String fallbackNotice) {
        public Outcome {
            mustAsk = mustAsk == null ? List.of() : List.copyOf(mustAsk);
        }
    }

    public Outcome compose(String sessionKey, String request) {
        List<String> fields = new ArrayList<>();
        sessions.start(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(sessions.activeSession(sessionKey));
        for (JangnyangSubtask s : def.subtasks()) fields.add(s.answerField());

        RequestExtraction extraction;
        try {
            extraction = interpreter.extract(request, fields);
            requireWellFormed(extraction);
        } catch (InterpreterException | IllegalArgumentException e) {
            // 조용히 기본값으로 채우지 않는다. 무엇으로 계산한 값인지 구별할 수 없게 된다.
            return new Outcome(FeasibilityVerdict.ok(), mustAskAll(def), true,
                    "요청 해석기를 쓸 수 없어 문항으로 진행합니다 (" + e.getMessage() + ")");
        }

        FeasibilityVerdict verdict = FeasibilityGate.judge(extraction);
        if (!verdict.feasible()) {
            // 거부한 요청으로 세션을 남기면, 다음 요청이 그 세션을 이어받는다.
            sessions.cancel(sessionKey);
            return new Outcome(verdict, List.of(), false, null);
        }

        SpanVerifier.Verified verified = SpanVerifier.verify(request, extraction);
        for (ExtractedValue v : verified.accepted()) {
            String id = idOfField(def, v.field());
            if (id == null) continue;   // 없는 필드를 낸 것은 버린다
            // 기존 검증기를 그대로 통과해야 한다. LLM 값에 예외를 두면 근거 없는 값이 흘러든다.
            sessions.submit(sessionKey, id, v.value(), null, SubtaskAnswerSource.LLM_NORMALIZED);
        }

        Set<String> answered = sessions.activeSession(sessionKey).answers().keySet();
        GapResolver.Resolution gaps = GapResolver.resolve(def, answered);

        List<String> mustAsk = new ArrayList<>(gaps.mustAsk());
        for (ExtractedValue r : verified.rejected()) {
            if (!mustAsk.contains(r.field())) mustAsk.add(r.field());
        }
        return new Outcome(verdict, mustAsk, false, null);
    }

    /** 형식이 깨진 추출은 전체를 버린다 — 반쯤 읽은 결과를 쓰면 무엇이 빠졌는지 모른다. */
    private static void requireWellFormed(RequestExtraction e) {
        if (e == null) throw new IllegalArgumentException("추출 결과가 없습니다");
        for (ExtractedValue v : e.values()) {
            if (v.field() == null || v.field().isBlank()) {
                throw new IllegalArgumentException("필드 이름이 없는 추출값이 있습니다");
            }
        }
    }

    private static List<String> mustAskAll(JangnyangSubtaskDefinition def) {
        List<String> all = new ArrayList<>();
        for (JangnyangSubtask s : def.subtasks()) all.add(s.answerField());
        return all;
    }

    private static String idOfField(JangnyangSubtaskDefinition def, String field) {
        for (JangnyangSubtask s : def.subtasks()) {
            if (s.answerField().equals(field)) return s.id();
        }
        return null;
    }
}
```

- [ ] **Step 5: OpenAI 구현체를 만든다**

`src/main/java/com/wastesim/llm/OpenAiRequestInterpreter.java`:

```java
package com.wastesim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.service.OpenAiService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM으로 요청을 해석한다.
 *
 * <p>프롬프트가 요구하는 것은 셋이다 — 필드, 값, 그리고 <b>근거가 된 원문 조각.</b>
 * 조각을 요구하는 이유는 {@link SpanVerifier}가 그것을 검사해 지어낸 값을 버리기
 * 때문이다. 조각 없이 값만 받으면 그 검사를 할 수 없다.
 */
@Component
@Primary
public class OpenAiRequestInterpreter implements RequestInterpreter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenAiService openAi;

    public OpenAiRequestInterpreter(OpenAiService openAi) {
        this.openAi = openAi;
    }

    @Override
    public RequestExtraction extract(String request, List<String> answerFields)
            throws InterpreterException {
        String prompt = """
                아래 요청에서 시뮬레이션 설정값을 뽑아 JSON으로만 답하라.

                규칙:
                - 요청에 없는 값은 절대 만들지 마라. 확실하지 않으면 빼라.
                - 값마다 근거가 된 요청의 원문 조각을 span에 그대로 옮겨라.
                - span은 요청 문장에 실제로 있는 문자열이어야 한다.

                형식:
                {"values":[{"field":"...","value":...,"span":"..."}],
                 "targetRegion":"","targetDomain":"","requestedConclusion":""}

                쓸 수 있는 field: %s

                요청: %s
                """.formatted(String.join(", ", answerFields), request);
        try {
            String raw = openAi.answerPlain(List.of(), prompt);
            JsonNode n = MAPPER.readTree(raw);
            List<ExtractedValue> values = new ArrayList<>();
            for (JsonNode v : n.path("values")) {
                values.add(new ExtractedValue(
                        v.path("field").asText(null),
                        v.path("value").isNumber() ? v.path("value").numberValue()
                                                   : v.path("value").asText(null),
                        v.path("span").asText(null)));
            }
            return new RequestExtraction(values,
                    n.path("targetRegion").asText(null),
                    n.path("targetDomain").asText(null),
                    n.path("requestedConclusion").asText(null));
        } catch (Exception e) {
            throw new InterpreterException("요청 해석에 실패했습니다", e);
        }
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./mvnw test -Dtest=BlueprintComposerTest`
Expected: PASS (6 tests)

- [ ] **Step 7: 전체 테스트와 Spring 컨텍스트 확인**

Run: `./mvnw test`
Expected: 575 tests, 0 failures

`McpToolExposureTest`가 Spring 컨텍스트를 띄우므로, `BlueprintComposer`가 `RequestInterpreter` 빈을 못 찾으면 여기서 실패한다. `OpenAiRequestInterpreter`에 `@Component @Primary`가 있으므로 주입되어야 한다. 실패하면 생성자 주입 대상이 모호한지 확인한다.

- [ ] **Step 8: 변이로 방어를 확인한다**

```bash
# ① 거부 후 cancel을 지운다 (세션이 남는다)
#    Expected: refusedRequestDoesNotStartASession 실패
# ② rejected 값도 submit 한다
#    Expected: unverifiedValuesGoToMustAsk 실패 (버린 값이 채워진다)
# ③ 폴백에서 GapResolver의 autoFilled를 채워 넣는다
#    Expected: llmFailureFallsBackLoudlyNotSilently 실패
# ④ requireWellFormed를 no-op으로 만든다
#    Expected: malformedExtractionIsDiscardedWholesale 실패
# ⑤ submit의 source를 USER_DIRECT로 바꾼다
#    Expected: onlyVerifiedValuesEnterTheSessionAndAreMarkedAsLlm 실패
# 각각 확인 후 되돌린다
```

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/wastesim/llm/ src/test/java/com/wastesim/llm/
git commit -m "feat(llm): 요청 하나를 설계도로 옮기는 흐름을 완성한다

순서가 설계의 전부다: 뽑고(LLM) -> 판정하고(코드) -> 인용을 확인하고(코드) -> 기존
검증기에 넘기고 -> 남은 것을 근거로 가른다.

LLM 값도 기존 JangnyangSubtaskValidator를 그대로 통과한다. LLM 값에 검증 예외를 두면
근거 없는 값이 흘러들고, 검증 로직이 두 벌이 되면 어제 잡은 결함이 되돌아온다.

LLM이 죽으면 기본값으로 채우지 않는다. 문항 흐름으로 넘기고 그 이유를 사용자에게 알린다 —
조용한 폴백은 무엇으로 계산한 값인지 구별할 수 없게 만든다.

형식이 깨진 추출은 전체를 버린다. 반쯤 읽은 결과를 쓰면 무엇이 빠졌는지 알 수 없다.

거부한 요청으로 세션을 남기지 않는다. 남기면 다음 요청이 그 세션을 이어받는다.

테스트에서 LLM을 부르지 않는다 — RequestInterpreter를 인터페이스로 두고 고정 응답
스텁으로 흐름을 검증한다. TMAP·OSRM에 쓴 방식과 같다."
```

---

## Self-Review

**1. 스펙 커버리지**

| 스펙 절 | 구현 태스크 |
|---|---|
| §4.1 전체 흐름과 신뢰 경계 | Task 7 (`BlueprintComposer`) |
| §4.1 판정용 추출 필드 | Task 5 (`RequestExtraction`) |
| §4.2 근거 선언 여섯 종류 | Task 2 |
| §4.2 동작 셋 (채움/표시/묻기) | Task 3 (`GapResolver`), Task 4 (표시) |
| §4.3 거부 게이트 네 사유 | Task 6 |
| §4.3 부족한 것 목록 | Task 6 (`regionNeeds`) |
| §4.4 ① 인용 강제 | Task 5 (`SpanVerifier`) |
| §4.4 ② 형식 깨짐 → 전체 버림 | Task 7 (`requireWellFormed`) |
| §4.4 ③ 서비스 장애 → 시끄러운 폴백 | Task 7 (`usedFallback`) |
| §4.4 ④ 범위 밖 값 → 기존 검증기 | Task 7 (`submit` 경유) |
| §4.5 다중 실행 | **2단계 — 이 계획 밖** |
| §6 V-L1 | Task 5 |
| §6 V-L2 | Task 7 |
| §6 V-L3 | Task 3 |
| §6 V-L4 | Task 7 |
| §6 V-L5 | **2단계** |
| §6 V-L6 | Task 6 |

빈 곳 없음. §4.5와 V-L5는 스펙 §4.6이 2단계로 미룬 것이다.

**2. 빈칸 스캔** — "TBD"·"적절히"·"에러 처리 추가" 없음. 모든 코드 단계에 실제 코드가 있다.

**3. 타입 일관성**

- `SubtaskAnswerSource` — Task 1이 인자로 받고 Task 7이 `LLM_NORMALIZED`로 넘긴다 ✓
- `GapResolver.Resolution.mustAsk()` — Task 3이 `List<String>`(필드 이름), Task 7이 그대로 쓴다 ✓
- `SpanVerifier.Verified.rejected()` — `List<ExtractedValue>`, Task 7이 `.field()`로 이름을 꺼낸다 ✓
- `FeasibilityVerdict.ok()` — Task 6이 정의, Task 7이 폴백에서 쓴다 ✓
- `AppliedDefault(field, value, reason)` — Task 3 Step 3에 실제 성분 확인 명령을 넣었다 ✓
- `SimulationResult` 생성자 — Task 4 Step 1에 확인 명령을 넣었다 ✓

**주의로 남긴 두 곳:** Task 2 Step 9(v4가 `latest()`가 되어 기존 테스트가 깨질 수 있음)와 Task 7 Step 7(Spring 빈 주입)은 실패 시 대처를 단계에 적어 뒀다.
