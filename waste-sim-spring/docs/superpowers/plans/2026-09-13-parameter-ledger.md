# 매개변수 결정 원장 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매개변수 하나마다 값·상태·출처·변환 이력을 append-only로 쌓고, 구조가 바뀌면 종속 값을 낡은 것으로 표시하며, 조립된 `SimulationConfig`를 원장과 대조해 실행을 차단한다.

**Architecture:** 새 패키지 `com.wastesim.ledger`(원장·활성 규칙·재계산·역검증)와 `com.wastesim.registry`(등록 계약 검증)를 만든다. 기존 `BasisKind`·`SubtaskAnswerSource`·`SubtaskState`·`PesFlattener`·`JangnyangScenarioBuilder`는 **읽기만 하고 수정하지 않는다.** 원장은 기존 타입을 입력으로 받아 상태를 계산하는 소비자이지, 그것들을 대체하지 않는다.

**Tech Stack:** Java 21 · Spring Boot · JUnit 5 (`org.junit.jupiter.api.Assertions.*`) · Maven wrapper

**Spec:** `docs/superpowers/specs/2026-09-13-asset-contract-ledger-design.md`

## Global Constraints

- `SimulationEngine`·`SimulationConfig`·python 어댑터를 **수정하지 않는다.** 이 계획의 변경은 `com.wastesim.ledger`, `com.wastesim.registry`, 그 테스트에 한정한다.
- `com.wastesim.ses`와 `com.wastesim.subtask`의 기존 타입을 **수정하지 않는다.** 읽기만 한다.
- 서브태스크 세트 리소스(`jangnyang-simulator-v*.json`)를 덮어쓰지 않는다 (D-45).
- 새 컴파일 경로를 만들지 않는다. `PesFlattener`/`JangnyangScenarioBuilder`가 유일한 컴파일 타깃이다.
- 테스트 실행: `./mvnw test -Dtest=<클래스명>` · 전체는 `./mvnw test`
- 주석과 문서는 한국어로 쓴다. **무엇을 하는지가 아니라 왜 그렇게 했는지**를 적는다 — 기존 파일의 문체를 따른다.
- 모든 커밋 메시지는 한국어 제목 한 줄 + 본문. 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `ledger/DecisionState.java` | 결정의 상태 7종과 실행 허용 판정 |
| `ledger/ValueSource.java` | 값이 어디서 왔는가 |
| `ledger/Transformation.java` | 어떤 규칙으로 변환됐는가 |
| `ledger/ParameterDecision.java` | 결정 레코드 하나와 그 불변식 |
| `ledger/ParameterLedger.java` | append-only 저장소 |
| `ledger/DecisionStateMapper.java` | 기존 `BasisKind`·`SubtaskAnswerSource` → 상태 |
| `ledger/Activation.java` | `ACTIVE`·`INACTIVE`·`UNKNOWN` |
| `ledger/RuleRegistry.java` | 등록된 결정론적 규칙과 3값 평가 |
| `ledger/JangnyangRules.java` | 장량동 활성 규칙 등록 |
| `ledger/LedgerRecalculator.java` | 무효화 트리거와 재계산 |
| `registry/AssetContract.java` | 자산 등록 계약 |
| `registry/RegistrationIssue.java` | 거부/보고 항목 |
| `registry/RegistrationValidator.java` | 등록 검증 다섯 규칙 |
| `registry/SimulationConfigFields.java` | 실제 입력 필드 열거 (계약 밖을 보는 자리) |
| `ledger/verify/BackVerificationResult.java` | 역검증 결과 |
| `ledger/verify/ConfigBackVerifier.java` | 컴파일 결과 ↔ 원장 대조 |
| `ledger/mcp/ParameterExpectation.java` | 매개변수가 기대하는 의미·단위·시간창 |
| `ledger/mcp/ToolCandidate.java` | MCP 후보값 |
| `ledger/mcp/CandidateAdmission.java` | 후보값 승격 검사 |

---

### Task 1: 결정 레코드와 불변식

**Files:**
- Create: `src/main/java/com/wastesim/ledger/DecisionState.java`
- Create: `src/main/java/com/wastesim/ledger/ValueSource.java`
- Create: `src/main/java/com/wastesim/ledger/Transformation.java`
- Create: `src/main/java/com/wastesim/ledger/ParameterDecision.java`
- Test: `src/test/java/com/wastesim/ledger/ParameterDecisionTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `DecisionState.executable()`, `ParameterDecision` 전 필드, `ValueSource(type, reference, version, acquiredAt)`, `Transformation(ruleRef, inputEventRefs)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/ParameterDecisionTest.java`:

```java
package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 레코드가 자기 불변식을 지키는가. 생성 시점에 막지 않으면 근거 없는 결정이
 * 원장에 들어앉고, 원장에 들어앉은 뒤에는 실행 직전 검사가 그것을 근거 있는
 * 것으로 읽는다.
 */
class ParameterDecisionTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final ValueSource USER = new ValueSource("user_explicit", "ST-01", "v4", T);

    @Test
    void 실행_허용_상태는_셋뿐이다() {
        assertTrue(DecisionState.CONFIRMED.executable());
        assertTrue(DecisionState.DERIVED.executable());
        assertTrue(DecisionState.DEFAULTED.executable());
        assertFalse(DecisionState.UNRESOLVED.executable());
        assertFalse(DecisionState.CONFLICTED.executable());
        assertFalse(DecisionState.INVALID.executable());
        assertFalse(DecisionState.STALE.executable());
    }

    @Test
    void derived는_변환_규칙_없이_만들_수_없다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.DERIVED,
                        "7일", null, 7, "day", USER, null, List.of(), null, null, T));
        assertTrue(e.getMessage().contains("변환 규칙"), e.getMessage());
    }

    @Test
    void 실행_불가_상태는_차단_사유_없이_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.UNRESOLVED,
                        null, null, null, null, null, null, List.of(), null, null, T));
    }

    @Test
    void stale은_무엇_때문에_낡았는지_적어야_한다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.STALE,
                        null, null, 7, "day", USER, null, List.of(),
                        "upstream_value_changed", null, T));
    }

    @Test
    void 실행_허용_상태는_출처_없이_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.CONFIRMED,
                        "7", null, 7, "day", null, null, List.of(), null, null, T));
    }

    @Test
    void 근거를_갖춘_확정값은_만들어진다() {
        ParameterDecision d = new ParameterDecision("d1", "sim::days", DecisionState.CONFIRMED,
                "7", null, 7, "day", USER, null, List.of("ST-01"), null, null, T);
        assertEquals("sim::days", d.parameterId());
        assertTrue(d.state().executable());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=ParameterDecisionTest`
Expected: 컴파일 실패 — `DecisionState`, `ValueSource`, `Transformation`, `ParameterDecision`을 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`DecisionState.java`:

```java
package com.wastesim.ledger;

/**
 * 매개변수 결정 하나가 놓인 자리.
 *
 * <p><b>왜 상태를 값과 따로 두는가</b>: 값이 있다는 것과 그 값으로 실행해도 된다는 것은
 * 다른 사실이다. 둘을 한 필드로 뭉뚱그리면 "값이 채워져 있다"가 곧 "확인됐다"로 읽힌다 —
 * MCP가 돌려준 검사 미통과 값과 사용자가 직접 넣은 값이 구분되지 않는다.
 */
public enum DecisionState {

    /** 아직 값이 없다. 질문하거나 차단한다. */
    UNRESOLVED,

    /** 사용자가 직접 넣었다. */
    CONFIRMED,

    /** 다른 값에서 등록된 규칙으로 유도했다. 변환 규칙 ID가 반드시 붙는다. */
    DERIVED,

    /** 근거 있는 기본값으로 채웠다. */
    DEFAULTED,

    /** 두 출처가 다른 값을 준다. 고르지 않고 막는다. */
    CONFLICTED,

    /** 값이 있으나 검사를 통과하지 못했다. */
    INVALID,

    /**
     * 낡았다. 구조나 상위 값이 바뀌어 이 결정을 더는 믿을 수 없다.
     *
     * <p>값을 지우지 않고 상태만 바꾸는 이유는, 무엇이 있었는지 알아야 무엇이 바뀌었는지
     * 말할 수 있기 때문이다.
     */
    STALE;

    /**
     * 이 상태로 실행해도 되는가.
     *
     * <p>셋만 통과시킨다. 나머지를 통과시키는 예외를 한 번이라도 두면, 그 예외가 곧
     * 근거 없는 값이 실행에 도달하는 경로가 된다.
     */
    public boolean executable() {
        return this == CONFIRMED || this == DERIVED || this == DEFAULTED;
    }
}
```

`ValueSource.java`:

```java
package com.wastesim.ledger;

import java.time.Instant;

/**
 * 이 값이 어디서 왔는가.
 *
 * <p>{@code reference}는 <b>밖에서 찾아가 대조할 수 있는 곳</b>을 가리킨다 —
 * {@link com.wastesim.subtask.BasisKind}가 규정·측정과 모델 기본값을 가르는 기준과 같다.
 *
 * @param type        {@code user_explicit} · {@code asset_contract} · {@code trusted_dataset}
 *                    · {@code calculated} · {@code mcp_result} · {@code not_applicable_by_rule}
 * @param reference   대조할 곳. 서브태스크 ID, 규정 문서, 도구 호출 ID
 * @param version     그 출처의 버전. 없으면 {@code null}
 * @param acquiredAt  언제 얻었는가. 만료 판정의 기준이다
 */
public record ValueSource(String type, String reference, String version, Instant acquiredAt) {

    public ValueSource {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("출처 종류가 없습니다.");
        }
    }
}
```

`Transformation.java`:

```java
package com.wastesim.ledger;

import java.util.List;

/**
 * 이 값을 무엇으로 어떻게 만들었는가.
 *
 * <p><b>왜 규칙 ID만 받는가</b>: 자연어 설명을 받으면 변환을 재현할 수 없고, 재현할 수 없는
 * 변환은 감사할 수 없다. 등록된 규칙만 참조하게 하면 "이 값이 어떻게 나왔는가"의 답이
 * 언제나 실행 가능한 형태로 남는다.
 *
 * @param ruleRef        등록된 변환 규칙 ID
 * @param inputEventRefs 이 변환이 읽은 결정들의 ID
 */
public record Transformation(String ruleRef, List<String> inputEventRefs) {

    public Transformation {
        if (ruleRef == null || ruleRef.isBlank()) {
            throw new IllegalArgumentException("변환 규칙 ID가 없습니다.");
        }
        inputEventRefs = inputEventRefs == null ? List.of() : List.copyOf(inputEventRefs);
    }
}
```

`ParameterDecision.java`:

```java
package com.wastesim.ledger;

import java.time.Instant;
import java.util.List;

/**
 * 매개변수 하나에 대한 결정 한 건. 원장에 쌓이는 단위다.
 *
 * <p><b>왜 불변식을 생성자에서 막는가</b>: 원장은 append-only라 한 번 들어간 레코드를
 * 고칠 수 없다. 들어간 뒤에 검사하면 고칠 방법이 없는 것을 발견하게 되므로, 들어가기
 * 전에 막는 자리가 여기뿐이다.
 *
 * @param decisionId       원장 안에서 유일한 ID. {@code supersededBy}가 이것을 가리킨다
 * @param parameterId      {@code <asset-id>::<input-field>}
 * @param rawValue         정규화 전 값. 사용자가 "7일"이라 답했으면 그 문자열
 * @param normalizedValue  정규화 후 값. 실행 설정에 들어갈 값
 * @param source           실행 허용 상태({@link DecisionState#executable()})면 필수
 * @param transformation   {@link DecisionState#DERIVED}면 필수
 * @param blockingReason   실행 불가 상태면 필수 — 왜 막혔는지 다음 사람이 알아야 한다
 * @param supersededBy     {@link DecisionState#STALE}이면 필수. 이 결정을 낡게 만든
 *                         트리거나 대체 결정의 참조
 */
public record ParameterDecision(
        String decisionId,
        String parameterId,
        DecisionState state,
        Object rawValue,
        String rawUnit,
        Object normalizedValue,
        String normalizedUnit,
        ValueSource source,
        Transformation transformation,
        List<String> evidenceRefs,
        String blockingReason,
        String supersededBy,
        Instant recordedAt) {

    public ParameterDecision {
        if (parameterId == null || parameterId.isBlank()) {
            throw new IllegalArgumentException("매개변수 ID가 없습니다.");
        }
        if (state == null) {
            throw new IllegalArgumentException("결정 상태가 없습니다.");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);

        if (state == DecisionState.DERIVED && transformation == null) {
            throw new IllegalArgumentException(
                    parameterId + ": 유도한 값인데 변환 규칙이 없습니다.");
        }
        if (state.executable() && source == null) {
            throw new IllegalArgumentException(
                    parameterId + ": 실행에 쓸 값인데 출처가 없습니다.");
        }
        if (!state.executable() && (blockingReason == null || blockingReason.isBlank())) {
            throw new IllegalArgumentException(
                    parameterId + ": 실행할 수 없는 상태인데 차단 사유가 없습니다.");
        }
        if (state == DecisionState.STALE && (supersededBy == null || supersededBy.isBlank())) {
            throw new IllegalArgumentException(
                    parameterId + ": 낡았다고 표시했는데 무엇 때문인지 없습니다.");
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=ParameterDecisionTest`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger src/test/java/com/wastesim/ledger
git commit -m "feat(ledger): 결정 레코드가 자기 근거를 생성 시점에 요구한다"
```

---

### Task 2: append-only 원장

**Files:**
- Create: `src/main/java/com/wastesim/ledger/ParameterLedger.java`
- Test: `src/test/java/com/wastesim/ledger/ParameterLedgerTest.java`

**Interfaces:**
- Consumes: Task 1의 `ParameterDecision`, `DecisionState`
- Produces: `ParameterLedger.append(ParameterDecision)`, `current(String)`, `history(String)`, `parameterIds()`, `blocking()`, `nextDecisionId(String)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/ParameterLedgerTest.java`:

```java
package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장이 덮어쓰지 않는가. 덮어쓰면 "왜 이 값으로 바뀌었는가"가 사라지고,
 * 그 질문은 결과가 이상할 때만 나오므로 그때는 이미 늦다.
 */
class ParameterLedgerTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");

    private static ParameterDecision confirmed(String id, String param, Object value) {
        return new ParameterDecision(id, param, DecisionState.CONFIRMED,
                value, null, value, null,
                new ValueSource("user_explicit", "ST-01", null, T),
                null, List.of(), null, null, T);
    }

    @Test
    void 새_결정은_이전_것을_지우지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(confirmed("d2", "sim::days", 14));

        assertEquals(2, ledger.history("sim::days").size());
        assertEquals(7, ledger.history("sim::days").get(0).normalizedValue());
    }

    @Test
    void 현재_값은_마지막_결정이다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(confirmed("d2", "sim::days", 14));

        assertEquals(14, ledger.current("sim::days").normalizedValue());
    }

    @Test
    void 결정이_없는_매개변수의_현재는_null이다() {
        assertNull(new ParameterLedger().current("sim::days"));
    }

    @Test
    void 같은_ID를_두_번_쌓을_수_없다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.append(confirmed("d1", "sim::seeds", 1)));
    }

    @Test
    void 실행을_막는_것들만_따로_모은다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(new ParameterDecision("d2", "sim::seeds", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));

        List<ParameterDecision> blocking = ledger.blocking();
        assertEquals(1, blocking.size());
        assertEquals("sim::seeds", blocking.get(0).parameterId());
    }

    @Test
    void 막힌_것이_나중에_풀리면_더는_막지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("d1", "sim::seeds", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));
        ledger.append(confirmed("d2", "sim::seeds", 1));

        assertEquals(List.of(), ledger.blocking());
        assertEquals(2, ledger.history("sim::seeds").size());
    }

    @Test
    void 결정_ID는_매개변수마다_이어서_붙는다() {
        ParameterLedger ledger = new ParameterLedger();
        assertEquals("sim::days#1", ledger.nextDecisionId("sim::days"));
        ledger.append(confirmed(ledger.nextDecisionId("sim::days"), "sim::days", 7));
        assertEquals("sim::days#2", ledger.nextDecisionId("sim::days"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=ParameterLedgerTest`
Expected: 컴파일 실패 — `ParameterLedger`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

```java
package com.wastesim.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매개변수별 결정 이력. <b>덮어쓰지 않는다.</b>
 *
 * <p><b>왜 현재 값을 따로 저장하지 않는가</b>: 저장하면 이력과 현재가 갈라질 수 있고,
 * 갈라지면 어느 쪽이 옳은지 판단할 근거가 없다. 현재는 언제나 <b>이력의 마지막</b>이므로
 * 계산해서 답한다 — 두 사실을 하나로 줄이면 어긋날 자리가 없어진다.
 *
 * <p>{@link com.wastesim.subtask.SubtaskState}가 시간 축에 대해 한 일과 같은 태도다.
 */
public final class ParameterLedger {

    private final Map<String, List<ParameterDecision>> byParameter = new LinkedHashMap<>();
    private final Set<String> usedIds = new LinkedHashSet<>();

    /** 결정을 쌓는다. 같은 ID를 두 번 쌓으면 이력이 가리키는 곳이 모호해지므로 거부한다. */
    public ParameterDecision append(ParameterDecision decision) {
        if (!usedIds.add(decision.decisionId())) {
            throw new IllegalArgumentException(
                    "이미 쓴 결정 ID입니다: " + decision.decisionId());
        }
        byParameter.computeIfAbsent(decision.parameterId(), k -> new ArrayList<>())
                .add(decision);
        return decision;
    }

    /** 이 매개변수의 현재 결정. 없으면 {@code null}. */
    public ParameterDecision current(String parameterId) {
        List<ParameterDecision> history = byParameter.get(parameterId);
        if (history == null || history.isEmpty()) return null;
        return history.get(history.size() - 1);
    }

    /** 쌓인 순서 그대로의 이력. */
    public List<ParameterDecision> history(String parameterId) {
        return List.copyOf(byParameter.getOrDefault(parameterId, List.of()));
    }

    public List<String> parameterIds() {
        return List.copyOf(byParameter.keySet());
    }

    /**
     * 지금 실행을 막는 결정들. 이력이 아니라 <b>현재</b>만 본다 — 과거에 막혔다가 풀린
     * 것까지 세면 영원히 실행할 수 없다.
     */
    public List<ParameterDecision> blocking() {
        List<ParameterDecision> blocking = new ArrayList<>();
        for (String id : byParameter.keySet()) {
            ParameterDecision now = current(id);
            if (now != null && !now.state().executable()) blocking.add(now);
        }
        return List.copyOf(blocking);
    }

    /** 다음 결정 ID. 매개변수마다 1부터 센다 — 사람이 이력을 읽을 때 순서가 보인다. */
    public String nextDecisionId(String parameterId) {
        return parameterId + "#" + (byParameter.getOrDefault(parameterId, List.of()).size() + 1);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=ParameterLedgerTest`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/ParameterLedger.java src/test/java/com/wastesim/ledger/ParameterLedgerTest.java
git commit -m "feat(ledger): 원장이 덮어쓰지 않고 쌓는다"
```

---

### Task 3: 기존 선언을 상태로 옮긴다

**Files:**
- Create: `src/main/java/com/wastesim/ledger/DecisionStateMapper.java`
- Test: `src/test/java/com/wastesim/ledger/DecisionStateMapperTest.java`

**Interfaces:**
- Consumes: `com.wastesim.subtask.BasisKind`, `com.wastesim.subtask.SubtaskAnswerSource`, Task 1의 `DecisionState`
- Produces: `DecisionStateMapper.map(SubtaskAnswerSource, BasisKind)` → `DecisionState`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/DecisionStateMapperTest.java`:

```java
package com.wastesim.ledger;

import com.wastesim.subtask.BasisKind;
import com.wastesim.subtask.SubtaskAnswerSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이미 있는 선언을 다시 만들지 않고 읽는가. {@link BasisKind}가 "묻지 않고 채울 수
 * 있는가"를 이미 판정하고 있으므로, 원장은 그 판정을 <b>뒤집지 않는다</b>.
 */
class DecisionStateMapperTest {

    @Test
    void 사용자가_직접_넣으면_확정이다() {
        assertEquals(DecisionState.CONFIRMED,
                DecisionStateMapper.map(SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE));
    }

    @Test
    void LLM이_정규화했으면_유도다() {
        assertEquals(DecisionState.DERIVED,
                DecisionStateMapper.map(SubtaskAnswerSource.LLM_NORMALIZED, BasisKind.NONE));
    }

    @Test
    void 서버가_모델_기본값으로_채우면_기본값이다() {
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT,
                        BasisKind.MODEL_DEFAULT));
    }

    @Test
    void 규정과_측정도_채울_수_있으므로_기본값이다() {
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT,
                        BasisKind.REGULATION));
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT,
                        BasisKind.MEASURED));
    }

    @Test
    void 근거가_없으면_서버가_채워도_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.NONE));
    }

    @Test
    void 실험_목적은_서버가_채울_수_있는_성질이_아니다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT,
                        BasisKind.EXPERIMENT_INTENT));
    }

    @Test
    void 답이_없으면_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(null, BasisKind.MODEL_DEFAULT));
    }

    @Test
    void 선언이_없으면_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(SubtaskAnswerSource.SERVER_DEFAULT, null));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=DecisionStateMapperTest`
Expected: 컴파일 실패 — `DecisionStateMapper`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

```java
package com.wastesim.ledger;

import com.wastesim.subtask.BasisKind;
import com.wastesim.subtask.SubtaskAnswerSource;

/**
 * 답변 시점의 두 사실을 원장의 상태로 옮긴다.
 *
 * <p><b>왜 판정을 다시 만들지 않는가</b>: {@link BasisKind#canFillWithoutAsking()}이
 * "묻지 않고 채울 수 있는가"를 이미 판정하고, 그 판정이 세트 해시가 덮는 자산이다.
 * 여기서 다른 기준을 쓰면 같은 필드에 대해 두 개의 답이 생기고, 갈라졌을 때 어느 쪽이
 * 옳은지 말할 근거가 없다.
 *
 * <p>그래서 이 매퍼는 <b>옮기기만 한다.</b> 판단은 {@code BasisKind}에 있다.
 */
public final class DecisionStateMapper {

    private DecisionStateMapper() { }

    public static DecisionState map(SubtaskAnswerSource source, BasisKind basis) {
        if (source == null) return DecisionState.UNRESOLVED;

        return switch (source) {
            case USER_DIRECT -> DecisionState.CONFIRMED;
            case LLM_NORMALIZED -> DecisionState.DERIVED;
            // 선언이 없으면 근거를 모른다는 뜻이므로 채우지 않는다 —
            // FieldBasis.unknown()이 누락을 NONE으로 보는 것과 같은 이유다.
            case SERVER_DEFAULT -> (basis != null && basis.canFillWithoutAsking())
                    ? DecisionState.DEFAULTED
                    : DecisionState.UNRESOLVED;
        };
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=DecisionStateMapperTest`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/DecisionStateMapper.java src/test/java/com/wastesim/ledger/DecisionStateMapperTest.java
git commit -m "feat(ledger): BasisKind의 판정을 뒤집지 않고 상태로 옮긴다"
```

---

### Task 4: 3값 활성 평가

**Files:**
- Create: `src/main/java/com/wastesim/ledger/Activation.java`
- Create: `src/main/java/com/wastesim/ledger/RuleRegistry.java`
- Create: `src/main/java/com/wastesim/ledger/JangnyangRules.java`
- Test: `src/test/java/com/wastesim/ledger/RuleRegistryTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `Activation.{ACTIVE,INACTIVE,UNKNOWN}`, `RuleRegistry.register(String, Rule)`, `knows(String)`, `evaluate(String, Map<String,Object>)`, `ruleIds()`, `RuleRegistry.fieldEquals(String, Object)`, `JangnyangRules.registry()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/RuleRegistryTest.java`:

```java
package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미확정을 비활성으로 접지 않는가. 접으면 아무 답도 하지 않은 실행이 조용히
 * "그 가지는 필요 없다"는 가정을 쓴다.
 */
class RuleRegistryTest {

    @Test
    void 조건이_맞으면_활성이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.ACTIVE,
                rules.evaluate("traffic-apply", Map.of("trafficMode", "APPLY")));
    }

    @Test
    void 조건이_틀리면_비활성이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.INACTIVE,
                rules.evaluate("traffic-apply", Map.of("trafficMode", "IGNORE")));
    }

    @Test
    void 답이_아직_없으면_모름이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.UNKNOWN, rules.evaluate("traffic-apply", Map.of()));
    }

    @Test
    void 답이_null이어도_모름이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        Map<String, Object> answers = new HashMap<>();
        answers.put("trafficMode", null);
        assertEquals(Activation.UNKNOWN, rules.evaluate("traffic-apply", answers));
    }

    @Test
    void 등록하지_않은_규칙은_평가하지_않고_던진다() {
        RuleRegistry rules = new RuleRegistry();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> rules.evaluate("없는규칙", Map.of()));
        assertTrue(e.getMessage().contains("없는규칙"), e.getMessage());
    }

    @Test
    void 아는_규칙인지_물어볼_수_있다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertTrue(rules.knows("traffic-apply"));
        assertFalse(rules.knows("없는규칙"));
    }

    @Test
    void 같은_규칙_ID를_두_번_등록할_수_없다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertThrows(IllegalArgumentException.class, () ->
                rules.register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "X")));
    }

    @Test
    void 장량동_규칙집은_교통_활성_규칙을_안다() {
        assertTrue(JangnyangRules.registry().knows(JangnyangRules.TRAFFIC_APPLY));
        assertEquals(Activation.ACTIVE, JangnyangRules.registry()
                .evaluate(JangnyangRules.TRAFFIC_APPLY, Map.of("trafficMode", "APPLY")));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=RuleRegistryTest`
Expected: 컴파일 실패 — `Activation`, `RuleRegistry`, `JangnyangRules`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`Activation.java`:

```java
package com.wastesim.ledger;

/**
 * 이 가지가 살아 있는가.
 *
 * <p><b>왜 참·거짓이 아니라 셋인가</b>: 조건이 의존하는 값이 아직 없을 때 거짓을 돌려주면,
 * "아직 모른다"와 "필요 없다"가 같은 답이 된다. 그러면 아무 답도 하지 않은 실행이 모든
 * 조건부 가지를 건너뛰고 통과한다.
 */
public enum Activation {

    /** 살아 있다. 이 가지의 필수값을 물어야 한다. */
    ACTIVE,

    /** 죽었다. 이 가지의 필수값은 해당 없음으로 확정할 수 있다. */
    INACTIVE,

    /** 판단할 값이 아직 없다. <b>비활성으로 접지 않는다.</b> */
    UNKNOWN
}
```

`RuleRegistry.java`:

```java
package com.wastesim.ledger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 결정론적 활성 규칙.
 *
 * <p><b>왜 자연어 조건을 실행하지 않는가</b>: {@code Coupling.activeWhen}이 지금 들고 있는
 * 문자열은 사람이 읽는 설명이다. 그것을 해석해 실행하면 해석기가 곧 또 하나의 추론 경로가
 * 되고, 추론 경로는 이 프로젝트가 값 결정에서 막아 온 바로 그것이다. 그래서 조건은 등록된
 * ID로만 참조하고, <b>모르는 ID는 평가하지 않고 던진다</b> — 조용히 비활성으로 처리하면
 * 오타 하나가 가지 하나를 통째로 없앤다.
 */
public final class RuleRegistry {

    /** 답변들을 보고 이 가지가 살아 있는지 판정한다. */
    @FunctionalInterface
    public interface Rule {
        Activation evaluate(Map<String, Object> answers);
    }

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public RuleRegistry register(String ruleId, Rule rule) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("규칙 ID가 없습니다.");
        }
        if (rules.putIfAbsent(ruleId, rule) != null) {
            throw new IllegalArgumentException("이미 등록된 규칙 ID입니다: " + ruleId);
        }
        return this;
    }

    public boolean knows(String ruleId) {
        return rules.containsKey(ruleId);
    }

    public Set<String> ruleIds() {
        return Set.copyOf(rules.keySet());
    }

    public Activation evaluate(String ruleId, Map<String, Object> answers) {
        Rule rule = rules.get(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("등록되지 않은 규칙 ID입니다: " + ruleId);
        }
        return rule.evaluate(answers == null ? Map.of() : answers);
    }

    /**
     * 필드 하나가 기대값과 같은가.
     *
     * <p>값이 없거나 {@code null}이면 {@link Activation#UNKNOWN}이다 — 없는 것을
     * "다르다"로 읽으면 미답이 곧 비활성이 된다.
     */
    public static Rule fieldEquals(String field, Object expected) {
        return answers -> {
            if (!answers.containsKey(field) || answers.get(field) == null) {
                return Activation.UNKNOWN;
            }
            return expected.equals(answers.get(field)) ? Activation.ACTIVE : Activation.INACTIVE;
        };
    }
}
```

`JangnyangRules.java`:

```java
package com.wastesim.ledger;

/**
 * 장량동 시뮬레이터의 활성 규칙집.
 *
 * <p>지금 조건부 결합은 교통 둘뿐이다({@code SesPruner} 주석 참고). 규칙을 미리 늘리지
 * 않는 이유는, 쓰이지 않는 규칙은 틀려도 아무도 모르기 때문이다.
 */
public final class JangnyangRules {

    /** 교통 결합이 사는 조건. 답변 하나가 값이 아니라 연결 구조를 바꾸는 자리다. */
    public static final String TRAFFIC_APPLY = "traffic-apply";

    private static final RuleRegistry REGISTRY = new RuleRegistry()
            .register(TRAFFIC_APPLY, RuleRegistry.fieldEquals("trafficMode", "APPLY"));

    private JangnyangRules() { }

    public static RuleRegistry registry() {
        return REGISTRY;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=RuleRegistryTest`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/Activation.java src/main/java/com/wastesim/ledger/RuleRegistry.java src/main/java/com/wastesim/ledger/JangnyangRules.java src/test/java/com/wastesim/ledger/RuleRegistryTest.java
git commit -m "feat(ledger): 미확정 조건을 비활성으로 접지 않는다"
```

---

### Task 5: 무효화와 재계산

**Files:**
- Create: `src/main/java/com/wastesim/ledger/LedgerRecalculator.java`
- Test: `src/test/java/com/wastesim/ledger/LedgerRecalculatorTest.java`

**Interfaces:**
- Consumes: Task 1~4 전부
- Produces: `new LedgerRecalculator(RuleRegistry, Map<String,String> activeWhenByParameter, Map<String,List<String>> dependents)`, `onAnswerChanged(ParameterLedger, String changedParameterId, Map<String,Object> answers)` → `List<ParameterDecision>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/LedgerRecalculatorTest.java`:

```java
package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구조를 바꾸는 답변이 들어왔을 때 이미 받은 값들의 유효성을 다시 따지는가.
 * 이것이 이 계획의 본체다 — 지금 코드에는 이 자리가 없다.
 */
class LedgerRecalculatorTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");

    private static ParameterDecision confirmed(String id, String param, Object value) {
        return new ParameterDecision(id, param, DecisionState.CONFIRMED,
                value, null, value, null,
                new ValueSource("user_explicit", "ST-01", null, T),
                null, List.of(), null, null, T);
    }

    private LedgerRecalculator recalculator() {
        return new LedgerRecalculator(
                JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of("sim::trafficMode", List.of("sim::trafficProfileId")));
    }

    @Test
    void 상위_값이_바뀌면_종속_결정이_낡는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.STALE, now.state());
        assertEquals("sim::trafficMode", now.supersededBy());
        assertEquals("upstream_value_changed", now.blockingReason());
    }

    @Test
    void 낡은_값은_지워지지_않고_이력에_남는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        assertEquals("P1", ledger.history("sim::trafficProfileId").get(0).normalizedValue());
        assertEquals("P1", ledger.current("sim::trafficProfileId").normalizedValue());
    }

    @Test
    void 가지가_살아나면_새_필수값이_미해결로_드러난다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("required_value_unresolved", now.blockingReason());
    }

    @Test
    void 가지가_죽으면_해당없음으로_확정되고_근거가_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "IGNORE"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.DEFAULTED, now.state());
        assertEquals("not_applicable_by_rule", now.source().type());
        assertEquals(JangnyangRules.TRAFFIC_APPLY, now.source().reference());
        assertTrue(now.state().executable());
    }

    @Test
    void 조건이_모름이면_필수값은_미해결로_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode", Map.of());

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("activation_unknown", now.blockingReason());
    }

    @Test
    void 이미_같은_상태면_같은_레코드를_거듭_쌓지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = recalculator();

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        assertEquals(1, ledger.history("sim::trafficProfileId").size());
    }

    @Test
    void 바뀐_결과들을_돌려준다() {
        ParameterLedger ledger = new ParameterLedger();
        List<ParameterDecision> changed = recalculator()
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "APPLY"));

        assertEquals(1, changed.size());
        assertEquals("sim::trafficProfileId", changed.get(0).parameterId());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=LedgerRecalculatorTest`
Expected: 컴파일 실패 — `LedgerRecalculator`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

```java
package com.wastesim.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구조를 바꾸는 답변이 들어왔을 때 원장을 다시 계산한다.
 *
 * <p><b>왜 이 자리가 필요한가</b>: 지금 수집 경로는 답변을 받아 검증하고 조립하지만,
 * 뒤에 온 답변이 앞서 받은 답변의 전제를 무너뜨렸는지 따지지 않는다. 그래서 교통을 켠
 * 뒤 끄면 교통 프로필 값이 남아 있고, 끈 뒤 켜면 프로필을 묻지 않은 채로 조립이 된다.
 *
 * <p>상태 기계는 건드리지 않는다 — {@code SubtaskState}는 {@code READY → COLLECTING}과
 * {@code BUILT → COLLECTING}을 이미 허용한다("답을 고치면 다시 수집으로"). 여기서 하는
 * 일은 그 전이를 <b>일으켜야 할 때를 알아내는 것</b>이다.
 */
public final class LedgerRecalculator {

    private final RuleRegistry rules;
    private final Map<String, String> activeWhenByParameter;
    private final Map<String, List<String>> dependents;

    /**
     * @param activeWhenByParameter 매개변수 → 그 가지를 살리는 규칙 ID
     * @param dependents            매개변수 → 이 값이 바뀌면 낡는 매개변수들
     */
    public LedgerRecalculator(RuleRegistry rules,
                              Map<String, String> activeWhenByParameter,
                              Map<String, List<String>> dependents) {
        this.rules = rules;
        this.activeWhenByParameter = Map.copyOf(activeWhenByParameter);
        this.dependents = Map.copyOf(dependents);
        for (String ruleId : activeWhenByParameter.values()) {
            if (!rules.knows(ruleId)) {
                throw new IllegalArgumentException("등록되지 않은 규칙 ID입니다: " + ruleId);
            }
        }
    }

    /**
     * 답변 하나가 바뀌었다. 낡은 것을 표시하고 활성 구조를 다시 계산한다.
     *
     * @return 이번에 새로 쌓인 결정들
     */
    public List<ParameterDecision> onAnswerChanged(ParameterLedger ledger,
                                                   String changedParameterId,
                                                   Map<String, Object> answers) {
        Instant now = Instant.now();
        List<ParameterDecision> appended = new ArrayList<>();

        // 1) 종속 결정을 낡은 것으로 표시한다. 값은 지우지 않는다 —
        //    무엇이 있었는지 알아야 무엇이 바뀌었는지 말할 수 있다.
        for (String dependent : dependents.getOrDefault(changedParameterId, List.of())) {
            ParameterDecision current = ledger.current(dependent);
            if (current == null || !current.state().executable()) continue;
            appended.add(ledger.append(new ParameterDecision(
                    ledger.nextDecisionId(dependent), dependent, DecisionState.STALE,
                    current.rawValue(), current.rawUnit(),
                    current.normalizedValue(), current.normalizedUnit(),
                    current.source(), current.transformation(), current.evidenceRefs(),
                    "upstream_value_changed", changedParameterId, now)));
        }

        // 2) 활성 구조를 다시 계산한다.
        for (Map.Entry<String, String> e : activeWhenByParameter.entrySet()) {
            String parameterId = e.getKey();
            Activation activation = rules.evaluate(e.getValue(), answers);
            ParameterDecision next = decisionFor(ledger, parameterId, e.getValue(), activation, now);
            if (next != null) appended.add(ledger.append(next));
        }

        return List.copyOf(appended);
    }

    /** 이미 같은 결론이면 {@code null} — 같은 레코드를 거듭 쌓으면 이력이 잡음이 된다. */
    private ParameterDecision decisionFor(ParameterLedger ledger, String parameterId,
                                          String ruleId, Activation activation, Instant now) {
        ParameterDecision current = ledger.current(parameterId);

        return switch (activation) {
            case ACTIVE -> (current != null && current.state().executable()
                    && !"not_applicable_by_rule".equals(current.source().type()))
                    ? null
                    : blocked(ledger, parameterId, "required_value_unresolved", current, now);

            // 비활성 가지는 묻지 않고 해당 없음으로 확정한다. 세트에서 지우지 않는 이유는
            // 50항목을 생략 없이 유지한다는 규약이 세트 해시의 전제이기 때문이다.
            case INACTIVE -> (current != null && current.state() == DecisionState.DEFAULTED
                    && "not_applicable_by_rule".equals(current.source().type()))
                    ? null
                    : new ParameterDecision(
                            ledger.nextDecisionId(parameterId), parameterId,
                            DecisionState.DEFAULTED, null, null, null, null,
                            new ValueSource("not_applicable_by_rule", ruleId, null, now),
                            null, List.of(), null, null, now);

            case UNKNOWN -> blocked(ledger, parameterId, "activation_unknown", current, now);
        };
    }

    private ParameterDecision blocked(ParameterLedger ledger, String parameterId,
                                      String reason, ParameterDecision current, Instant now) {
        if (current != null && current.state() == DecisionState.UNRESOLVED
                && reason.equals(current.blockingReason())) {
            return null;
        }
        return new ParameterDecision(
                ledger.nextDecisionId(parameterId), parameterId, DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(), reason, null, now);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=LedgerRecalculatorTest`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/LedgerRecalculator.java src/test/java/com/wastesim/ledger/LedgerRecalculatorTest.java
git commit -m "feat(ledger): 구조가 바뀌면 종속 값을 낡은 것으로 표시한다"
```

---

### Task 6: 등록 검증 — 계약 밖을 보는 검사

**Files:**
- Create: `src/main/java/com/wastesim/registry/AssetContract.java`
- Create: `src/main/java/com/wastesim/registry/RegistrationIssue.java`
- Create: `src/main/java/com/wastesim/registry/SimulationConfigFields.java`
- Create: `src/main/java/com/wastesim/registry/RegistrationValidator.java`
- Test: `src/test/java/com/wastesim/registry/RegistrationValidatorTest.java`

**Interfaces:**
- Consumes: Task 4의 `RuleRegistry`, 기존 `com.wastesim.model.SimulationConfig`(읽기 전용 리플렉션)
- Produces: `AssetContract` 레코드, `RegistrationIssue(Severity, rule, detail)`, `SimulationConfigFields.all()` → `Set<String>`, `RegistrationValidator.validate(AssetContract, RuleRegistry, Set<String> adapterIds)` → `List<RegistrationIssue>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/registry/RegistrationValidatorTest.java`:

```java
package com.wastesim.registry;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 계약이 자기 자신을 증명하지 못한다는 사실을 검사로 만든 자리.
 *
 * <p>다른 규칙들은 계약 안의 일관성을 보지만 {@code checkInputBindingCoverage}만
 * 계약 <b>밖</b>을 본다 — 실제 입력 필드를 열거해 계약이 덮지 않는 것을 보고한다.
 */
class RegistrationValidatorTest {

    private static final Set<String> ADAPTERS = Set.of("jangnyang-adapter");

    private static AssetContract.Builder ok() {
        return AssetContract.builder()
                .assetId("jangnyang-simulator")
                .version("v4")
                .contentDigest("sha256:0000")
                .status(AssetContract.Status.PROPOSED)
                .adapterRefs(List.of("jangnyang-adapter"))
                .ruleRefs(List.of(JangnyangRules.TRAFFIC_APPLY))
                .boundInputFields(SimulationConfigFields.all());
    }

    private static List<RegistrationIssue> validate(AssetContract contract) {
        return new RegistrationValidator()
                .validate(contract, JangnyangRules.registry(), ADAPTERS);
    }

    private static List<String> rulesOf(List<RegistrationIssue> issues,
                                        RegistrationIssue.Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity)
                .map(RegistrationIssue::rule).sorted().toList();
    }

    @Test
    void 온전한_계약은_아무것도_걸리지_않는다() {
        assertEquals(List.of(), validate(ok().build()));
    }

    @Test
    void 미치환_placeholder는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().version("<verified-version>").build());
        assertEquals(List.of("rejectUnresolvedPlaceholders"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 미등록_규칙_ID는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().ruleRefs(List.of("없는규칙")).build());
        assertEquals(List.of("rejectUnknownRuleRefs"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 미등록_어댑터는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().adapterRefs(List.of("없는어댑터")).build());
        assertEquals(List.of("rejectUnknownAdapterRefs"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 증거_없는_자산은_verified가_될_수_없다() {
        List<RegistrationIssue> issues = validate(
                ok().status(AssetContract.Status.VERIFIED).evidenceRefs(List.of()).build());
        assertEquals(List.of("requireEvidenceBeforeVerified"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 증거가_있으면_verified가_된다() {
        assertEquals(List.of(), validate(ok()
                .status(AssetContract.Status.VERIFIED)
                .evidenceRefs(List.of("docs/research/s1-ses-extraction/reference-ses.json"))
                .build()));
    }

    @Test
    void 덮지_못한_입력_필드는_거부가_아니라_보고다() {
        List<RegistrationIssue> issues = validate(ok()
                .boundInputFields(List.of("days")).build());

        assertEquals(List.of(), rulesOf(issues, RegistrationIssue.Severity.REJECT));
        assertEquals(List.of("checkInputBindingCoverage"),
                rulesOf(issues, RegistrationIssue.Severity.REPORT));
        assertTrue(issues.get(0).detail().contains("seeds"), issues.get(0).detail());
    }

    @Test
    void 실제_입력_필드를_계약이_아니라_코드에서_읽는다() {
        Set<String> fields = SimulationConfigFields.all();
        assertTrue(fields.contains("days"));
        assertTrue(fields.contains("trafficEnabled"));
        assertTrue(fields.size() >= 40,
                "SimulationConfig의 세터가 40개 미만일 리 없다: " + fields.size());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=RegistrationValidatorTest`
Expected: 컴파일 실패 — `AssetContract`, `RegistrationIssue`, `SimulationConfigFields`, `RegistrationValidator`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`RegistrationIssue.java`:

```java
package com.wastesim.registry;

/**
 * 등록 검증에서 걸린 것 하나.
 *
 * <p><b>왜 거부와 보고를 가르는가</b>: 계약이 덮지 못한 입력 필드는 오류가 아니다 —
 * 계약을 늘릴지 그 필드가 이번 실험의 대상이 아닌지는 사람이 판단할 일이다. 전부 거부로
 * 만들면 판단할 자리가 사라지고, 전부 보고로 만들면 지어낸 ID가 실행 경로에 들어간다.
 */
public record RegistrationIssue(Severity severity, String rule, String detail) {

    public enum Severity {
        /** 등록할 수 없다. */
        REJECT,
        /** 등록은 되지만 사람이 봐야 한다. */
        REPORT
    }
}
```

`AssetContract.java`:

```java
package com.wastesim.registry;

import java.util.List;

/**
 * 자산 하나의 등록 계약.
 *
 * <p>상태가 둘뿐인 이유는 셋째 상태를 둘 근거가 없기 때문이다. 자동 추출 결과는 언제나
 * {@link Status#PROPOSED}에서 시작한다 — 추출에 성공했다는 것은 문장을 찾았다는 뜻이지
 * 그 값이 맞다는 뜻이 아니다. {@code SpanVerifier}가 인용 문자열의 포함 여부만 검사한다는
 * 사실이 이 구분의 근거다.
 *
 * @param boundInputFields 이 계약이 덮는다고 주장하는 실행 입력 필드들.
 *                         <b>주장이지 사실이 아니다</b> — 사실은
 *                         {@link SimulationConfigFields}가 코드에서 읽는다
 * @param trialGuarantees  시험 실행이 보증하는 범위. 비어 있으면 아무것도 보증하지 않는다
 */
public record AssetContract(
        String assetId,
        String version,
        String contentDigest,
        Status status,
        List<String> ruleRefs,
        List<String> adapterRefs,
        List<String> evidenceRefs,
        List<String> boundInputFields,
        boolean trialSupported,
        List<String> trialGuarantees) {

    public enum Status { PROPOSED, VERIFIED }

    public AssetContract {
        ruleRefs = ruleRefs == null ? List.of() : List.copyOf(ruleRefs);
        adapterRefs = adapterRefs == null ? List.of() : List.copyOf(adapterRefs);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        boundInputFields = boundInputFields == null ? List.of() : List.copyOf(boundInputFields);
        trialGuarantees = trialGuarantees == null ? List.of() : List.copyOf(trialGuarantees);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 테스트가 한 항목만 바꿔 가며 쓰기 위한 조립기. */
    public static final class Builder {
        private String assetId;
        private String version;
        private String contentDigest;
        private Status status = Status.PROPOSED;
        private List<String> ruleRefs = List.of();
        private List<String> adapterRefs = List.of();
        private List<String> evidenceRefs = List.of();
        private List<String> boundInputFields = List.of();
        private boolean trialSupported = false;
        private List<String> trialGuarantees = List.of();

        public Builder assetId(String v) { this.assetId = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder contentDigest(String v) { this.contentDigest = v; return this; }
        public Builder status(Status v) { this.status = v; return this; }
        public Builder ruleRefs(List<String> v) { this.ruleRefs = v; return this; }
        public Builder adapterRefs(List<String> v) { this.adapterRefs = v; return this; }
        public Builder evidenceRefs(List<String> v) { this.evidenceRefs = v; return this; }
        public Builder boundInputFields(java.util.Collection<String> v) {
            this.boundInputFields = List.copyOf(v); return this;
        }
        public Builder trialSupported(boolean v) { this.trialSupported = v; return this; }
        public Builder trialGuarantees(List<String> v) { this.trialGuarantees = v; return this; }

        public AssetContract build() {
            return new AssetContract(assetId, version, contentDigest, status, ruleRefs,
                    adapterRefs, evidenceRefs, boundInputFields, trialSupported, trialGuarantees);
        }
    }
}
```

`SimulationConfigFields.java`:

```java
package com.wastesim.registry;

import com.wastesim.model.SimulationConfig;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * 실행 설정이 실제로 받는 입력 필드. <b>계약이 아니라 코드에서 읽는다.</b>
 *
 * <p><b>왜 리플렉션인가</b>: 목록을 손으로 적으면 그 목록도 계약이 되고, 계약이 누락하면
 * 검증기도 같이 누락한다는 바로 그 문제를 한 겹 더 만든다. 세터를 직접 세면 코드가 바뀔
 * 때 목록도 함께 바뀐다 — 이 검사만은 사람의 선언을 거치지 않아야 의미가 있다.
 *
 * <p>{@code SimulationConfig}를 읽기만 하고 수정하지 않는다.
 */
public final class SimulationConfigFields {

    private SimulationConfigFields() { }

    /** 세터 이름에서 얻은 필드명 집합. {@code setDays} → {@code days}. */
    public static Set<String> all() {
        Set<String> fields = new TreeSet<>();
        for (Method m : SimulationConfig.class.getMethods()) {
            String name = m.getName();
            if (!name.startsWith("set") || name.length() <= 3) continue;
            if (m.getParameterCount() != 1) continue;
            fields.add(Character.toLowerCase(name.charAt(3)) + name.substring(4));
        }
        return fields;
    }
}
```

`RegistrationValidator.java`:

```java
package com.wastesim.registry;

import com.wastesim.ledger.RuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 등록 검증 다섯 규칙.
 *
 * <p>넷은 계약 안의 일관성을 보고, {@code checkInputBindingCoverage} 하나만 계약 밖을
 * 본다. <b>그 하나가 이 클래스의 이유다</b> — 계약이 입력을 누락하면 그 계약으로 만든
 * 검증기도 같이 누락하므로, 계약을 정본으로 삼지 않는 검사가 최소 하나는 있어야 한다.
 */
public final class RegistrationValidator {

    /** {@code <verified-version>}처럼 채우지 않고 남겨 둔 자리. */
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^<>]+>");

    public List<RegistrationIssue> validate(AssetContract contract,
                                            RuleRegistry rules,
                                            Set<String> adapterIds) {
        List<RegistrationIssue> issues = new ArrayList<>();

        rejectUnresolvedPlaceholders(contract, issues);
        rejectUnknownRuleRefs(contract, rules, issues);
        rejectUnknownAdapterRefs(contract, adapterIds, issues);
        requireEvidenceBeforeVerified(contract, issues);
        checkInputBindingCoverage(contract, issues);

        return List.copyOf(issues);
    }

    private void rejectUnresolvedPlaceholders(AssetContract c, List<RegistrationIssue> issues) {
        List<String> found = new ArrayList<>();
        for (String value : allStrings(c)) {
            if (value != null && PLACEHOLDER.matcher(value).find()) found.add(value);
        }
        if (!found.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnresolvedPlaceholders",
                    "채우지 않은 자리가 남아 있습니다: " + found));
        }
    }

    private void rejectUnknownRuleRefs(AssetContract c, RuleRegistry rules,
                                       List<RegistrationIssue> issues) {
        List<String> unknown = c.ruleRefs().stream().filter(r -> !rules.knows(r)).sorted().toList();
        if (!unknown.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnknownRuleRefs", "등록되지 않은 규칙입니다: " + unknown));
        }
    }

    private void rejectUnknownAdapterRefs(AssetContract c, Set<String> adapterIds,
                                          List<RegistrationIssue> issues) {
        List<String> unknown = c.adapterRefs().stream()
                .filter(a -> !adapterIds.contains(a)).sorted().toList();
        if (!unknown.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnknownAdapterRefs", "등록되지 않은 어댑터입니다: " + unknown));
        }
    }

    private void requireEvidenceBeforeVerified(AssetContract c, List<RegistrationIssue> issues) {
        if (c.status() == AssetContract.Status.VERIFIED && c.evidenceRefs().isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "requireEvidenceBeforeVerified",
                    "증거 없이 확인됨으로 올릴 수 없습니다: " + c.assetId()));
        }
    }

    /**
     * 계약이 덮지 못한 실제 입력 필드를 보고한다. <b>거부가 아니다</b> — 계약을 늘릴지
     * 그 필드가 이번 실험의 대상이 아닌지는 사람이 판단한다.
     */
    private void checkInputBindingCoverage(AssetContract c, List<RegistrationIssue> issues) {
        Set<String> uncovered = new TreeSet<>(SimulationConfigFields.all());
        uncovered.removeAll(Set.copyOf(c.boundInputFields()));
        if (!uncovered.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REPORT,
                    "checkInputBindingCoverage",
                    "계약이 덮지 않는 실행 입력 필드 " + uncovered.size() + "개: " + uncovered));
        }
    }

    private List<String> allStrings(AssetContract c) {
        List<String> values = new ArrayList<>(
                List.of(String.valueOf(c.assetId()), String.valueOf(c.version()),
                        String.valueOf(c.contentDigest())));
        values.addAll(c.ruleRefs());
        values.addAll(c.adapterRefs());
        values.addAll(c.evidenceRefs());
        values.addAll(c.trialGuarantees());
        return values;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=RegistrationValidatorTest`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/registry src/test/java/com/wastesim/registry
git commit -m "feat(registry): 계약이 아니라 코드에서 실제 입력 필드를 읽는다"
```

---

### Task 7: 역검증 — 컴파일 결과를 원장과 대조

**Files:**
- Create: `src/main/java/com/wastesim/ledger/verify/BackVerificationResult.java`
- Create: `src/main/java/com/wastesim/ledger/verify/ConfigBackVerifier.java`
- Test: `src/test/java/com/wastesim/ledger/verify/ConfigBackVerifierTest.java`

**Interfaces:**
- Consumes: Task 1·2, 기존 `com.wastesim.model.SimulationConfig`(읽기 전용 리플렉션)
- Produces: `BackVerificationResult(List<String> blocks)`, `passed()`, `ConfigBackVerifier.verify(SimulationConfig, ParameterLedger, Map<String,String> fieldToParameterId)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/verify/ConfigBackVerifierTest.java`:

```java
package com.wastesim.ledger.verify;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.ledger.ValueSource;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 검증된 계획이 잘못된 실행 설정으로 바뀌는 오류는 계획 검증으로 잡히지 않는다 —
 * 계획 쪽은 전부 통과하기 때문이다. 역검증은 변환 자체를 의심하는 유일한 자리다.
 */
class ConfigBackVerifierTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final Map<String, String> BINDING = Map.of("days", "sim::days");

    private static ParameterLedger ledgerWithDays(int value) {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days", DecisionState.CONFIRMED,
                String.valueOf(value), null, value, "day",
                new ValueSource("user_explicit", "ST-02", null, T),
                null, List.of(), null, null, T));
        return ledger;
    }

    private static SimulationConfig configWithDays(int value) {
        SimulationConfig config = new SimulationConfig();
        config.setDays(value);
        return config;
    }

    @Test
    void 원장과_같은_값이면_통과한다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), ledgerWithDays(7), BINDING);
        assertTrue(r.passed(), r.blocks().toString());
    }

    @Test
    void 컴파일_결과가_한_필드라도_다르면_막는다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(14), ledgerWithDays(7), BINDING);

        assertFalse(r.passed());
        assertEquals(1, r.blocks().size());
        assertTrue(r.blocks().get(0).contains("sim::days"), r.blocks().get(0));
    }

    @Test
    void 원장에_결정이_없는_값은_막는다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), new ParameterLedger(), BINDING);

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("결정이 없습니다"), r.blocks().get(0));
    }

    @Test
    void 실행할_수_없는_상태의_값은_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));

        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), ledger, BINDING);

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("UNRESOLVED"), r.blocks().get(0));
    }

    @Test
    void 막힌_사유를_전부_모은다() {
        SimulationConfig config = new SimulationConfig();
        config.setDays(14);
        config.setNumTrucks(3);

        BackVerificationResult r = new ConfigBackVerifier().verify(config, ledgerWithDays(7),
                Map.of("days", "sim::days", "numTrucks", "sim::numTrucks"));

        assertEquals(2, r.blocks().size());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=ConfigBackVerifierTest`
Expected: 컴파일 실패 — `BackVerificationResult`, `ConfigBackVerifier`를 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`BackVerificationResult.java`:

```java
package com.wastesim.ledger.verify;

import java.util.List;

/**
 * 역검증 결과. 막은 사유를 <b>전부</b> 모은다.
 *
 * <p>첫 불일치에서 멈추면 고치고 다시 돌리기를 반복해야 하고, 그 반복 중에 나중 불일치가
 * 앞의 것 때문에 생긴 것인지 따로 있는 것인지 구분되지 않는다.
 */
public record BackVerificationResult(List<String> blocks) {

    public BackVerificationResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public boolean passed() {
        return blocks.isEmpty();
    }
}
```

`ConfigBackVerifier.java`:

```java
package com.wastesim.ledger.verify;

import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.model.SimulationConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기존 빌더가 만든 실행 설정을 다시 열어 원장과 한 필드씩 맞춘다.
 *
 * <p><b>왜 새 컴파일러를 만들지 않고 결과만 보는가</b>: 컴파일 경로를 새로 내면
 * {@code DerivedSetVsV4ReportTest}가 고정한 대조 기준이 셋이 되고, 셋이 어긋났을 때 어느
 * 것이 옳은지 판단할 근거가 없다. 기존 경로를 그대로 두고 <b>그 산출물을 의심하는</b>
 * 자리만 두면 기준은 둘로 유지된다.
 *
 * <p>{@code SimulationConfig}를 읽기만 하고 수정하지 않는다.
 */
public final class ConfigBackVerifier {

    /**
     * @param fieldToParameterId 실행 설정의 필드명 → 원장의 매개변수 ID
     */
    public BackVerificationResult verify(SimulationConfig config,
                                         ParameterLedger ledger,
                                         Map<String, String> fieldToParameterId) {
        List<String> blocks = new ArrayList<>();

        for (Map.Entry<String, String> e : fieldToParameterId.entrySet()) {
            String field = e.getKey();
            String parameterId = e.getValue();
            Object configValue = read(config, field);
            if (configValue == null) continue;

            ParameterDecision decision = ledger.current(parameterId);
            if (decision == null) {
                blocks.add(field + "=" + configValue + ": 원장에 결정이 없습니다 ("
                        + parameterId + ")");
                continue;
            }
            if (!decision.state().executable()) {
                blocks.add(parameterId + ": 실행할 수 없는 상태입니다 — " + decision.state()
                        + " (" + decision.blockingReason() + ")");
                continue;
            }
            if (decision.source() == null) {
                blocks.add(parameterId + ": 확정값인데 출처가 없습니다");
                continue;
            }
            if (!configValue.equals(decision.normalizedValue())) {
                blocks.add(parameterId + ": 설정은 " + configValue + "인데 원장은 "
                        + decision.normalizedValue() + "입니다");
            }
        }

        return new BackVerificationResult(blocks);
    }

    /** 게터가 없거나 읽을 수 없으면 {@code null} — 없는 것은 대조 대상이 아니다. */
    private Object read(SimulationConfig config, String field) {
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = SimulationConfig.class.getMethod(prefix + capitalized);
                return getter.invoke(config);
            } catch (ReflectiveOperationException ignored) {
                // 다음 접두사를 시도한다
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=ConfigBackVerifierTest`
Expected: PASS (5 tests)

만약 `numTrucks`의 게터가 `getNumTrucks`가 아니어서 다섯 번째 테스트가 1건만 모으면, `SimulationConfig`를 열어 실제 게터 이름을 확인하고 테스트의 필드명을 그것에 맞춘다. **`SimulationConfig`는 고치지 않는다.**

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/verify src/test/java/com/wastesim/ledger/verify
git commit -m "feat(ledger): 컴파일 결과를 원장과 대조해 변환 자체를 의심한다"
```

---

### Task 8: MCP 후보값 승격

**Files:**
- Create: `src/main/java/com/wastesim/ledger/mcp/ParameterExpectation.java`
- Create: `src/main/java/com/wastesim/ledger/mcp/ToolCandidate.java`
- Create: `src/main/java/com/wastesim/ledger/mcp/CandidateAdmission.java`
- Test: `src/test/java/com/wastesim/ledger/mcp/CandidateAdmissionTest.java`

**Interfaces:**
- Consumes: Task 1·2
- Produces: `ParameterExpectation(parameterId, semanticType, unit, timeWindow, maxAge)`, `ToolCandidate(purposeField, value, unit, semanticType, timeWindow, observedAt, source)`, `CandidateAdmission.admit(ToolCandidate, ParameterExpectation, String decisionId, Instant now)`, `CandidateAdmission.onTimeout(ParameterExpectation, String decisionId, Instant now)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/mcp/CandidateAdmissionTest.java`:

```java
package com.wastesim.ledger.mcp;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 결과가 후보값으로만 들어오는가.
 *
 * <p>도구의 출력 스키마는 선택 사항이고 멱등성 표시는 보증이 아니라 힌트다. 그러므로
 * 의미·단위·시간창·최신성 검사는 프로토콜이 주는 것이 아니라 <b>이 자리가 해야 하는 일</b>이다.
 */
class CandidateAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private static final ParameterExpectation EXPECT = new ParameterExpectation(
            "sim::routeTravelMinutes", "travel_time", "minute", "daily_average",
            Duration.ofDays(30));

    private static ToolCandidate candidate(String unit, String semanticType,
                                           String timeWindow, Instant observedAt) {
        return new ToolCandidate("routeTravelMinutes", 12.5, unit, semanticType, timeWindow,
                observedAt, new ValueSource("mcp_result", "tmap#call-1", "v1", observedAt));
    }

    @Test
    void 전부_맞으면_확정된다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.CONFIRMED, d.state());
        assertEquals(12.5, d.normalizedValue());
    }

    @Test
    void 단위가_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("second", "travel_time", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("단위"), d.blockingReason());
    }

    @Test
    void 단위가_같아도_시간창이_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "peak_hour", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("시간창"), d.blockingReason());
    }

    @Test
    void 의미_타입이_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "distance", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("의미"), d.blockingReason());
    }

    @Test
    void 너무_오래된_값은_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "daily_average", NOW.minus(Duration.ofDays(60))),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("최신성"), d.blockingReason());
    }

    @Test
    void 쓸_곳이_다른_값은_받지_않는다() {
        ToolCandidate wrongPurpose = new ToolCandidate("intraZoneTravelMinutes", 12.5,
                "minute", "travel_time", "daily_average", NOW,
                new ValueSource("mcp_result", "tmap#call-1", "v1", NOW));

        ParameterDecision d = new CandidateAdmission()
                .admit(wrongPurpose, EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("쓸 곳"), d.blockingReason());
    }

    @Test
    void 타임아웃은_재요청하지_않고_미해결로_돌린다() {
        ParameterDecision d = new CandidateAdmission()
                .onTimeout(EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.UNRESOLVED, d.state());
        assertEquals("tool_timeout", d.blockingReason());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=CandidateAdmissionTest`
Expected: 컴파일 실패 — `ParameterExpectation`, `ToolCandidate`, `CandidateAdmission`을 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`ParameterExpectation.java`:

```java
package com.wastesim.ledger.mcp;

import java.time.Duration;

/**
 * 이 매개변수가 외부 값에 대해 기대하는 것.
 *
 * <p>호출 <b>전에</b> 정해 둔다. 결과가 온 뒤에 쓸 곳을 정하면 "평균 통행시간"과
 * "혼잡 시간대 통행시간"이 같은 필드에 들어간다 — 단위가 같아서 검사를 통과한다.
 *
 * @param timeWindow {@code daily_average} · {@code peak_hour} 등. 단위와 별개의 사실이다
 * @param maxAge     이보다 오래된 관측은 받지 않는다
 */
public record ParameterExpectation(String parameterId, String semanticType, String unit,
                                   String timeWindow, Duration maxAge) { }
```

`ToolCandidate.java`:

```java
package com.wastesim.ledger.mcp;

import com.wastesim.ledger.ValueSource;

import java.time.Instant;

/**
 * MCP가 돌려준 값. <b>아직 확정값이 아니다.</b>
 *
 * @param purposeField 이 값을 어디에 쓰기로 하고 불렀는가. 호출 시점에 고정된다
 */
public record ToolCandidate(String purposeField, Object value, String unit, String semanticType,
                            String timeWindow, Instant observedAt, ValueSource source) { }
```

`CandidateAdmission.java`:

```java
package com.wastesim.ledger.mcp;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 후보값을 원장에 올릴지 판정한다.
 *
 * <p>검사를 통과하지 못한 값도 원장에 <b>들어간다</b> — {@link DecisionState#INVALID}로.
 * 버리면 "물어봤는데 왜 값이 없는가"의 답이 사라지고, 다음 사람이 같은 도구를 다시 부른다.
 */
public final class CandidateAdmission {

    public ParameterDecision admit(ToolCandidate candidate, ParameterExpectation expectation,
                                   String decisionId, Instant now) {
        String rejection = reject(candidate, expectation, now);
        if (rejection != null) {
            return new ParameterDecision(decisionId, expectation.parameterId(),
                    DecisionState.INVALID, candidate.value(), candidate.unit(),
                    null, null, null, null, List.of(), rejection, null, now);
        }
        return new ParameterDecision(decisionId, expectation.parameterId(),
                DecisionState.CONFIRMED, candidate.value(), candidate.unit(),
                candidate.value(), expectation.unit(), candidate.source(),
                null, List.of(), null, null, now);
    }

    /**
     * 타임아웃. <b>재요청하지 않는다</b> — 중복 실행 방지가 보증되지 않는 이상 재시도는
     * 같은 작업을 두 번 시키는 것일 수 있다.
     */
    public ParameterDecision onTimeout(ParameterExpectation expectation,
                                       String decisionId, Instant now) {
        return new ParameterDecision(decisionId, expectation.parameterId(),
                DecisionState.UNRESOLVED, null, null, null, null, null, null,
                List.of(), "tool_timeout", null, now);
    }

    /** 막을 이유. 없으면 {@code null}. */
    private String reject(ToolCandidate c, ParameterExpectation e, Instant now) {
        if (!e.parameterId().endsWith("::" + c.purposeField())) {
            return "쓸 곳이 다릅니다: " + c.purposeField() + " → " + e.parameterId();
        }
        if (!e.semanticType().equals(c.semanticType())) {
            return "의미 타입이 다릅니다: " + c.semanticType() + " ≠ " + e.semanticType();
        }
        if (!e.unit().equals(c.unit())) {
            return "단위가 다릅니다: " + c.unit() + " ≠ " + e.unit();
        }
        if (!e.timeWindow().equals(c.timeWindow())) {
            return "시간창이 다릅니다: " + c.timeWindow() + " ≠ " + e.timeWindow();
        }
        if (c.observedAt() == null
                || Duration.between(c.observedAt(), now).compareTo(e.maxAge()) > 0) {
            return "최신성을 만족하지 않습니다: " + c.observedAt();
        }
        return null;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=CandidateAdmissionTest`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/mcp src/test/java/com/wastesim/ledger/mcp
git commit -m "feat(ledger): MCP 결과를 후보값으로 받고 쓸 곳을 먼저 고정한다"
```

---

### Task 9: 오류 주입과 과차단을 짝으로 시험

**Files:**
- Create: `src/test/java/com/wastesim/ledger/LedgerGateIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~8 전부
- Produces: 없음 (테스트만)

- [ ] **Step 1: 통합 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/LedgerGateIntegrationTest.java`:

```java
package com.wastesim.ledger;

import com.wastesim.ledger.verify.BackVerificationResult;
import com.wastesim.ledger.verify.ConfigBackVerifier;
import com.wastesim.model.SimulationConfig;
import com.wastesim.subtask.BasisKind;
import com.wastesim.subtask.SubtaskAnswerSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 오류 주입과 과차단을 <b>짝으로</b> 시험한다.
 *
 * <p>차단만 늘리면 "필수값 누락 0"과 "실행 성공률"이 동시에 올라가는 착시가 생긴다.
 * 정상 구성이 막히지 않는다는 것을 같은 파일에서 지키지 않으면, 이 원장은 안전해 보이는
 * 방식으로 쓸모없어질 수 있다.
 */
class LedgerGateIntegrationTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final Map<String, String> BINDING = Map.of("days", "sim::days");

    private static ParameterDecision answered(ParameterLedger ledger, String parameterId,
                                              Object value, SubtaskAnswerSource source,
                                              BasisKind basis) {
        DecisionState state = DecisionStateMapper.map(source, basis);
        return new ParameterDecision(
                ledger.nextDecisionId(parameterId), parameterId, state,
                value, null,
                state.executable() ? value : null, null,
                state.executable() ? new ValueSource("user_explicit", "ST-02", null, T) : null,
                state == DecisionState.DERIVED
                        ? new Transformation("normalize-days", List.of()) : null,
                List.of(),
                state.executable() ? null : "required_value_unresolved", null, T);
    }

    // ---- 과차단: 정상 구성은 막히지 않는다 ----

    @Test
    void 정상_골드_구성은_막히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE));

        SimulationConfig config = new SimulationConfig();
        config.setDays(7);

        BackVerificationResult r = new ConfigBackVerifier().verify(config, ledger, BINDING);
        assertTrue(r.passed(), r.blocks().toString());
        assertEquals(List.of(), ledger.blocking());
    }

    @Test
    void 모델_기본값으로_채운_값도_막히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.MODEL_DEFAULT));

        assertEquals(List.of(), ledger.blocking());
    }

    @Test
    void 비활성_가지는_묻지_않고_확정되어_실행을_막지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of())
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        assertEquals(List.of(), ledger.blocking());
    }

    // ---- 오류 주입: 알려진 오류는 전부 실행 전에 막힌다 ----

    @Test
    void 근거_없는_필수값은_실행을_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.NONE));

        assertEquals(1, ledger.blocking().size());
    }

    @Test
    void 컴파일_결과가_원장과_다르면_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE));

        SimulationConfig tampered = new SimulationConfig();
        tampered.setDays(14);

        assertFalse(new ConfigBackVerifier().verify(tampered, ledger, BINDING).passed());
    }

    @Test
    void 구조가_바뀌면_새_필수값이_드러나고_실행이_막힌다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of());

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        assertEquals(List.of(), ledger.blocking());

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "APPLY"));
        assertEquals(1, ledger.blocking().size(),
                "교통을 켰으면 프로필을 다시 물어야 한다");
    }

    @Test
    void 조건이_모름이면_가지를_건너뛰지_않고_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of())
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of());

        assertEquals(1, ledger.blocking().size());
        assertEquals("activation_unknown", ledger.blocking().get(0).blockingReason());
    }
}
```

- [ ] **Step 2: 실행해 통과를 확인한다**

Run: `./mvnw test -Dtest=LedgerGateIntegrationTest`
Expected: PASS (7 tests)

- [ ] **Step 3: 전체 테스트가 깨지지 않았는지 확인한다**

Run: `./mvnw test`
Expected: 기존 테스트 전부 통과. 이 계획은 기존 클래스를 수정하지 않으므로 실패가 나오면 **원인이 이 계획 밖에 있다** — 고치기 전에 먼저 `git stash`로 확인한다.

- [ ] **Step 4: 커밋한다**

```bash
git add src/test/java/com/wastesim/ledger/LedgerGateIntegrationTest.java
git commit -m "test(ledger): 오류 주입과 과차단을 한 파일에서 짝으로 지킨다"
```

---

## 이 계획이 만들지 않는 것

스펙이 계약으로만 명시하고 구현하지 않기로 한 것들이다. 다음 단계에서 자산이 둘 이상이 될 때 만든다.

- 자산 검색기와 recall 측정
- 다자산 의존성 폐포와 커플링 호환성 판정
- 시험 실행 실행기 (`trialSupported`는 계약 필드로만 존재하고 기본값 `false`)
- 의미 불변조건 평가기 (`semanticRuleRefs` 자리는 `RuleRegistry`가 이미 수용한다)
- MCP 브로커의 실제 도구 연결 (`CandidateAdmission`은 후보값을 받는 쪽만 구현한다)

## 자기 점검 결과

- **스펙 커버리지**: 결정 1 → Task 1·2·3, 결정 2 → Task 4·5, 결정 3 → Task 5, 결정 4 → Task 6, 결정 5(역검증) → Task 7, 결정 5(시험 실행 보증 범위) → Task 6의 `trialGuarantees` 필드, 결정 6 → Task 8, 테스트 표 → Task 9. 빠진 절 없음.
- **타입 일관성**: `ParameterDecision`의 13개 필드 순서가 Task 1·2·5·7·8·9에서 같다. `ValueSource(type, reference, version, acquiredAt)`, `Transformation(ruleRef, inputEventRefs)`도 같다. `DecisionState.executable()`은 정의된 이름 그대로 쓰인다.
- **placeholder**: 없음. 모든 코드 단계에 실제 코드가 있다.
- **알려진 위험**: Task 7 Step 4에 `SimulationConfig` 게터 이름이 다를 경우의 대응을 적어 두었다 — 테스트를 코드에 맞추고 `SimulationConfig`는 고치지 않는다.
