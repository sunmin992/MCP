# 원장 세션 연결 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미 구현된 `ParameterLedger`·`LedgerRecalculator`·`ConfigBackVerifier`를 `SubtaskSessionService`의 실제 답변 수집·조립·실행 경로에 연결해, 답변이 바뀌면 재계산이 돌고 실행 직전에 차단 검사와 역검증이 강제되게 한다.

**Architecture:** 원장은 `JangnyangSubtaskSession`의 필드로 살아 기존 store가 통째로 저장한다. 배선(자산 ID·활성 규칙·종속 관계·역검증 맵)은 새 클래스 `JangnyangLedgerWiring` 한자리에 모으고 `SesFieldMapping`에서 유도한다. 강제는 단계적이다 — `build()`는 경고만 싣고 `approveRun()`만 차단한다.

**Tech Stack:** Java 21 · Spring Boot · JUnit 5 (`org.junit.jupiter.api.Assertions.*`) · Maven wrapper

**Spec:** `docs/superpowers/specs/2026-09-14-ledger-session-integration-design.md`

## Global Constraints

- `SimulationEngine`·`SimulationConfig`·python 어댑터를 **수정하지 않는다.**
- `com.wastesim.ses`를 **수정하지 않는다.** 읽기만 한다 (`SesFieldMapping.bindings()`).
- `com.wastesim.ledger`·`com.wastesim.registry`의 기존 클래스를 **수정하지 않는다.** 이번 작업은 그것들을 호출하는 쪽만 만든다.
- 서브태스크 세트 리소스(`jangnyang-simulator-v*.json`)를 덮어쓰지 않는다 (D-45).
- **`approveRun(String)`의 반환 계약을 바꾸지 않는다** — BUILT가 아니거나 차단되면 `null`, 아니면 spec. 기존 호출부 5곳(`ChatController:682`, 테스트 4곳)이 여기 기대고 있다.
- `build()`가 지금 성공시키는 구성을 **새로 실패시키지 않는다.** 원장 판정은 경고로만 싣는다.
- 테스트 실행: 프로젝트 루트 `C:\Dev\MCP\waste-sim-spring` 에서 `.\mvnw.cmd test -Dtest=<클래스명>` (PowerShell 도구). 전체는 `.\mvnw.cmd test`
- 주석과 문서는 한국어로 쓴다. **무엇을 하는지가 아니라 왜 그렇게 했는지**를 적는다.
- 커밋 메시지는 한국어 제목 한 줄 + 본문, 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## 이미 있는 것 — 호출만 한다

| 타입 | 쓰는 것 |
|---|---|
| `com.wastesim.ledger.ParameterLedger` | `append(ParameterDecision)` · `current(String)` · `history(String)` · `parameterIds()` · `blocking()` · `nextDecisionId(String)` |
| `com.wastesim.ledger.AnswerDecisions` | `fromAnswer(decisionId, parameterId, rawValue, normalizedValue, SubtaskAnswerSource, BasisKind, ValueSource, Transformation, Instant)` → `ParameterDecision` |
| `com.wastesim.ledger.ParameterId` | `of(assetId, fieldName)` · `fieldOf(parameterId)` · `assetOf(parameterId)` · `SEPARATOR` |
| `com.wastesim.ledger.LedgerRecalculator` | `new LedgerRecalculator(RuleRegistry, Map<String,String> activeWhenByParameter, Map<String,List<String>> dependents)` · `onAnswerChanged(ledger, changedParameterId, Map<String,Object> answers)` |
| `com.wastesim.ledger.JangnyangRules` | `registry()` · `TRAFFIC_APPLY` · `TRAFFIC_MODE_FIELD` · `TRAFFIC_APPLY_VALUE` |
| `com.wastesim.ledger.verify.ConfigBackVerifier` | `verify(SimulationConfig, ParameterLedger, Map<String,String> fieldToParameterId)` → `BackVerificationResult` (`passed()` · `blocks()`) |
| `com.wastesim.ledger.ValueSource` | `new ValueSource(type, reference, version, acquiredAt)` |
| `com.wastesim.ledger.ParameterDecision` | `parameterId()` · `state()` · `blockingReason()` · `normalizedValue()` |

**주의:** `ConfigBackVerifier`는 매핑된 모든 필드에 `SimulationConfig`의 실제 게터가 있어야 한다. 게터가 없으면 차단 사유를 만든다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `subtask/JangnyangSubtaskSession.java` | 원장을 필드로 들고 접근자를 낸다 (수정) |
| `ledger/wiring/JangnyangLedgerWiring.java` | 자산 ID·활성 규칙·종속 관계·역검증 맵·제외 선언을 한자리에 (신규) |
| `subtask/SubtaskSessionService.java` | submit에서 기록·재계산, build에서 경고, approveRun에서 차단 (수정) |
| `subtask/RunApproval.java` | spec + 차단 사유 (신규) |

---

### Task 1: 세션이 원장을 든다

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/JangnyangSubtaskSession.java`
- Test: `src/test/java/com/wastesim/subtask/SessionLedgerTest.java`

**Interfaces:**
- Consumes: `com.wastesim.ledger.ParameterLedger`
- Produces: `JangnyangSubtaskSession.ledger()` → `ParameterLedger` (같은 인스턴스를 계속 돌려준다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/SessionLedgerTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.ParameterLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장이 세션과 같은 수명을 사는가.
 *
 * <p>별도 저장소로 빼면 세션만 저장되고 원장은 저장되지 않는 순간이 생기고, 그 순간에
 * 둘은 다른 사실을 말한다. 같은 객체에 매달아 두면 그 자리가 없어진다.
 */
class SessionLedgerTest {

    private static JangnyangSubtaskDefinition def() {
        return new JangnyangSubtaskCatalog().latest();
    }

    @Test
    void 새_세션의_원장은_비어_있다() {
        JangnyangSubtaskSession session = new JangnyangSubtaskSession("k", def());
        assertNotNull(session.ledger());
        assertEquals(List.of(), session.ledger().parameterIds());
    }

    @Test
    void 원장은_같은_인스턴스를_계속_돌려준다() {
        JangnyangSubtaskSession session = new JangnyangSubtaskSession("k", def());
        ParameterLedger first = session.ledger();
        assertSame(first, session.ledger(),
                "호출할 때마다 새 원장을 주면 쌓은 이력이 사라진다");
    }

    @Test
    void 세션마다_원장이_다르다() {
        JangnyangSubtaskSession a = new JangnyangSubtaskSession("a", def());
        JangnyangSubtaskSession b = new JangnyangSubtaskSession("b", def());
        assertNotSame(a.ledger(), b.ledger());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run (PowerShell 도구, `C:\Dev\MCP\waste-sim-spring`에서): `.\mvnw.cmd test -Dtest=SessionLedgerTest`
Expected: 컴파일 실패 — `ledger()` 메서드가 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`JangnyangSubtaskSession.java`의 필드 선언부에 추가한다. `private JangnyangScenarioSpec spec;` 바로 아래:

```java
    /**
     * 이 세션의 매개변수 결정 원장.
     *
     * <p><b>왜 세션이 들고 있는가</b>: 원장과 세션을 따로 저장하면 한쪽만 저장되는 순간이
     * 생기고, 그 순간에 둘은 다른 사실을 말한다. 같은 객체에 매달아 두면 {@code store.save}
     * 한 번이 둘 다 저장하므로 어긋날 자리가 없다.
     *
     * <p>{@code final}인 이유는 교체할 일이 없기 때문이다 — 원장은 append-only라 비우거나
     * 갈아 끼우는 연산 자체가 없다.
     */
    private final ParameterLedger ledger = new ParameterLedger();
```

그리고 `public JangnyangScenarioSpec spec() { return spec; }` 옆에 접근자를 추가한다:

```java
    /**
     * 이 세션의 원장. <b>복사본이 아니다</b> — 호출자가 여기에 결정을 쌓는다.
     *
     * <p>{@link #answers()}가 복사본을 주는 것과 다른 이유는, 답변 맵은 세션이 소유하고
     * 바깥이 읽기만 하는 반면 원장은 바깥(서비스)이 쓰는 자리이기 때문이다. 복사본을 주면
     * 쌓은 결정이 저장되지 않는다.
     */
    public ParameterLedger ledger() { return ledger; }
```

import를 추가한다: `import com.wastesim.ledger.ParameterLedger;`

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=SessionLedgerTest`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/JangnyangSubtaskSession.java src/test/java/com/wastesim/subtask/SessionLedgerTest.java
git commit -m "feat(subtask): 세션이 매개변수 결정 원장을 함께 들고 다닌다"
```

---

### Task 2: 배선을 한자리에 모은다

**Files:**
- Create: `src/main/java/com/wastesim/ledger/wiring/JangnyangLedgerWiring.java`
- Test: `src/test/java/com/wastesim/ledger/wiring/JangnyangLedgerWiringTest.java`

**Interfaces:**
- Consumes: `com.wastesim.ses.SesFieldMapping.bindings()` (읽기), `ParameterId`, `JangnyangRules`
- Produces: `JangnyangLedgerWiring.ASSET_ID` · `parameterIdOf(String answerField)` · `activeWhenByParameter()` · `dependents()` · `fieldToParameterId()` · `TRANSFORMED_FIELDS` (Map<String,String>: answerField → 제외 사유)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/ledger/wiring/JangnyangLedgerWiringTest.java`:

```java
package com.wastesim.ledger.wiring;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.registry.SimulationConfigFields;
import com.wastesim.ses.SesFieldMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배선이 대응표에서 유도되는가, 그리고 <b>유도되지 않는 자리가 선언돼 있는가</b>.
 *
 * <p>역검증이 보지 않는 필드를 "빠뜨림"이 아니라 "선언된 제외"로 두는 것이 이 클래스의
 * 요점이다. 둘이 코드에서 같아 보이면 다음 사람은 역검증이 전부를 본다고 믿는다.
 */
class JangnyangLedgerWiringTest {

    private static Set<String> allAnswerFields() {
        return SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField)
                .collect(Collectors.toSet());
    }

    @Test
    void 매개변수_ID는_자산_ID와_답변_필드로_만든다() {
        assertEquals("jangnyang-simulator::days",
                JangnyangLedgerWiring.parameterIdOf("days"));
    }

    @Test
    void 대응표의_모든_바인딩이_유도되거나_제외_선언돼_있다() {
        Set<String> verified = JangnyangLedgerWiring.fieldToParameterId().keySet();
        Set<String> excluded = JangnyangLedgerWiring.TRANSFORMED_FIELDS.keySet();

        List<String> unclassified = allAnswerFields().stream()
                .filter(f -> !verified.contains(f) && !excluded.contains(f))
                .sorted().toList();

        assertEquals(List.of(), unclassified,
                "대응표에 늘었는데 배선에서 분류되지 않은 필드가 있다: " + unclassified);
    }

    @Test
    void 제외_선언에는_반드시_이유가_적혀_있다() {
        for (Map.Entry<String, String> e : JangnyangLedgerWiring.TRANSFORMED_FIELDS.entrySet()) {
            assertNotNull(e.getValue(), e.getKey());
            assertFalse(e.getValue().isBlank(),
                    e.getKey() + ": 왜 대조할 수 없는지 적지 않으면 빠뜨린 것과 같아 보인다");
        }
    }

    @Test
    void 유도된_필드는_전부_SimulationConfig에_실재한다() {
        Set<String> real = SimulationConfigFields.all();
        List<String> missing = JangnyangLedgerWiring.fieldToParameterId().keySet().stream()
                .filter(f -> !real.contains(f)).sorted().toList();

        assertEquals(List.of(), missing,
                "역검증이 읽을 수 없는 필드를 매핑했다: " + missing);
    }

    @Test
    void 변환되는_필드는_역검증_맵에_들어가지_않는다() {
        for (String transformed : JangnyangLedgerWiring.TRANSFORMED_FIELDS.keySet()) {
            assertFalse(JangnyangLedgerWiring.fieldToParameterId().containsKey(transformed),
                    transformed + "은 값을 대조할 수 없는데 역검증 맵에 들어 있다");
        }
    }

    @Test
    void 교통_프로필은_교통_활성_규칙에_묶인다() {
        assertEquals(JangnyangRules.TRAFFIC_APPLY,
                JangnyangLedgerWiring.activeWhenByParameter()
                        .get(JangnyangLedgerWiring.parameterIdOf("trafficProfileId")));
    }

    @Test
    void 교통_모드가_바뀌면_프로필이_종속으로_따라온다() {
        assertEquals(List.of(JangnyangLedgerWiring.parameterIdOf("trafficProfileId")),
                JangnyangLedgerWiring.dependents()
                        .get(JangnyangLedgerWiring.parameterIdOf(JangnyangRules.TRAFFIC_MODE_FIELD)));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=JangnyangLedgerWiringTest`
Expected: 컴파일 실패 — `JangnyangLedgerWiring`을 찾을 수 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/wastesim/ledger/wiring/JangnyangLedgerWiring.java`:

```java
package com.wastesim.ledger.wiring;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.ParameterId;
import com.wastesim.registry.SimulationConfigFields;
import com.wastesim.ses.SesFieldMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장량동 시뮬레이터의 원장 배선. 자산 ID·활성 규칙·종속 관계·역검증 맵을 한자리에 둔다.
 *
 * <p><b>왜 유도하고 일부만 선언하는가</b>: 맵을 전부 손으로 적으면 그 맵도 계약이 되고,
 * 계약이 누락하면 그 계약으로 만든 검증기도 같이 누락한다 — {@code checkInputBindingCoverage}가
 * 막으려던 구조다. 이름이 같은 자리는 대응표에서 유도하고, 유도되지 않는 자리만 선언하면
 * 손으로 관리하는 면적이 다섯 줄로 줄고 그 다섯 줄이 낡으면 테스트가 먼저 깨진다.
 */
public final class JangnyangLedgerWiring {

    public static final String ASSET_ID = "jangnyang-simulator";

    /**
     * 빌더가 값을 옮기는 게 아니라 <b>변환하는</b> 답변 필드와, 그래서 역검증이 값을
     * 대조할 수 없는 이유.
     *
     * <p><b>왜 제외를 선언으로 두는가</b>: 빠뜨린 것과 일부러 뺀 것이 코드에서 같아 보이면
     * 다음 사람은 역검증이 대응표 전부를 본다고 믿는다. 실제로 보는 것은 유도된 자리뿐이다.
     *
     * <p>변환된 자리까지 대조하려면 변환 규칙을 등록해 기대값을 다시 계산해야 한다. 지금
     * 필요한 것은 그것이 아니라, 역검증이 <b>무엇을 보지 않는지가 드러나는 것</b>이다.
     */
    public static final Map<String, String> TRANSFORMED_FIELDS = Map.of(
            "scenarioType", "실행 규모·도구 선택으로 갈라진다 — 같은 이름의 설정 필드가 없다",
            "collectionSchedule", "값에 따라 collectionIntervalDays 또는 collectionDaysOfWeek로 갈라진다",
            "collectionTime", "수거 시각 목록(collectionTimesMinutes)으로 합쳐진다",
            "collectionTimes", "수거 시각 목록(collectionTimesMinutes)으로 합쳐진다",
            "occupationPreset", "프리셋 키가 비율 목록(occupationMix)이 된다");

    private JangnyangLedgerWiring() { }

    /** {@code jangnyang-simulator::days} 형태. 규약은 {@link ParameterId}가 소유한다. */
    public static String parameterIdOf(String answerField) {
        return ParameterId.of(ASSET_ID, answerField);
    }

    /**
     * 설정 필드명 → 매개변수 ID. <b>이름이 같고 실제 게터가 있는 것만</b> 담는다.
     *
     * <p>{@link SimulationConfigFields#all()}로 걸러 내는 이유는, 대응표에만 있고 실행
     * 설정에는 없는 이름을 넘기면 역검증이 "게터를 찾을 수 없습니다"로 정상 구성을 막기
     * 때문이다.
     */
    public static Map<String, String> fieldToParameterId() {
        Set<String> real = SimulationConfigFields.all();
        Map<String, String> map = new LinkedHashMap<>();
        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            String field = b.answerField();
            if (TRANSFORMED_FIELDS.containsKey(field)) continue;
            if (!real.contains(field)) continue;
            map.put(field, parameterIdOf(field));
        }
        return Map.copyOf(map);
    }

    /**
     * 매개변수 → 그 가지를 살리는 규칙 ID.
     *
     * <p>교통 프로필은 교통 결합이 살아 있을 때만 물어야 한다. 배선은 대응표가
     * {@code trafficMode}를 커플링 활성 지점에 묶어 둔 것을 읽은 것이지 새로 정한 것이 아니다.
     */
    public static Map<String, String> activeWhenByParameter() {
        return Map.of(parameterIdOf("trafficProfileId"), JangnyangRules.TRAFFIC_APPLY);
    }

    /**
     * 매개변수 → 이 값이 바뀌면 낡는 매개변수들.
     *
     * <p>교통을 껐다 켜면 이전 프로필 답변은 더 믿을 수 없다 — 다른 구조에서 고른 값이다.
     */
    public static Map<String, List<String>> dependents() {
        return Map.of(parameterIdOf(JangnyangRules.TRAFFIC_MODE_FIELD),
                List.of(parameterIdOf("trafficProfileId")));
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=JangnyangLedgerWiringTest`
Expected: PASS (7 tests)

만약 `대응표의_모든_바인딩이_유도되거나_제외_선언돼_있다`가 실패하면, 실패 메시지가 분류되지 않은 필드 이름을 알려 준다. 그 필드가 `SimulationConfig`에 같은 이름으로 있으면 자동으로 유도되므로, 실패했다는 것은 **이름이 다르다**는 뜻이다. `JangnyangScenarioBuilder`에서 그 필드가 어떤 설정 필드로 가는지 찾아 `TRANSFORMED_FIELDS`에 사유와 함께 추가한다. **테스트를 고치지 않는다.**

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/ledger/wiring src/test/java/com/wastesim/ledger/wiring
git commit -m "feat(ledger): 배선을 대응표에서 유도하고 대조 못 할 자리를 선언한다"
```

---

### Task 3: 답변마다 결정을 원장에 쌓는다

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java`
- Test: `src/test/java/com/wastesim/subtask/SubmitRecordsDecisionTest.java`

**Interfaces:**
- Consumes: Task 1의 `session.ledger()`, Task 2의 `JangnyangLedgerWiring.parameterIdOf`, `AnswerDecisions.fromAnswer`
- Produces: `SubtaskSessionService`가 submit 때 원장에 결정을 append한다 (새 public 메서드 없음)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/SubmitRecordsDecisionTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변이 원장에 남는가. 남지 않으면 나머지 모든 검사가 빈 원장 위에서 돌고,
 * 빈 원장은 아무것도 막지 않는다.
 */
class SubmitRecordsDecisionTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    @Test
    void 사용자가_답하면_확정_결정이_쌓인다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        assertNotNull(d, "답변이 원장에 남지 않았다");
        assertEquals(DecisionState.CONFIRMED, d.state());
        assertEquals(7, d.normalizedValue());
    }

    @Test
    void 출처가_원장에_남는다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        assertNotNull(d.source());
        assertEquals(subtaskId, d.source().reference(),
                "어느 질문의 답인지 남지 않으면 나중에 대조할 곳이 없다");
    }

    @Test
    void 검증에_실패한_답은_확정으로_쌓이지_않는다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, -5, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        if (d != null) {
            assertNotEquals(DecisionState.CONFIRMED, d.state(),
                    "검증기가 거부한 값이 원장에서 확정값이 되면 fail-closed가 무너진다");
        }
    }

    @Test
    void 답을_고치면_이력이_쌓인다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);
        sessions.submit("k", subtaskId, 14, null);

        var history = sessions.activeSession("k").ledger()
                .history(JangnyangLedgerWiring.parameterIdOf("days"));

        assertEquals(2, history.size(), "덮어쓰면 왜 바뀌었는지가 사라진다");
        assertEquals(7, history.get(0).normalizedValue());
        assertEquals(14, history.get(1).normalizedValue());
    }
}
```

이 테스트는 헬퍼 하나를 쓴다. 같은 커밋에 만든다 — `src/test/java/com/wastesim/subtask/SubtaskTestSupport.java`:

```java
package com.wastesim.subtask;

/**
 * 세션 테스트가 매번 조립하던 것을 한자리로 모은다. 서비스 조립과 "필드명으로 서브태스크
 * ID 찾기"는 세트 버전이 바뀌면 같이 바뀌는데, 테스트마다 복사해 두면 한 곳만 고치고
 * 나머지가 조용히 낡는다.
 */
final class SubtaskTestSupport {

    private SubtaskTestSupport() { }

    static SubtaskSessionService service() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        return new SubtaskSessionService(
                catalog,
                new JangnyangSubtaskValidator(),
                new JangnyangCompletenessChecker(),
                new JangnyangScenarioBuilder(),
                new InMemorySubtaskSessionStore());
    }

    /** 답변 필드명으로 이 세션의 서브태스크 ID를 찾는다. 없으면 {@code null}. */
    static String idOfField(SubtaskSessionService sessions, String sessionKey, String field) {
        JangnyangSubtaskSession session = sessions.activeSession(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(session);
        for (JangnyangSubtask s : def.ordered()) {
            if (field.equals(s.answerField())) return s.id();
        }
        return null;
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=SubmitRecordsDecisionTest`
Expected: FAIL — `사용자가_답하면_확정_결정이_쌓인다`에서 `d`가 `null`이다 ("답변이 원장에 남지 않았다")

`SubtaskTestSupport.service()`가 컴파일되지 않으면 `SubtaskSessionService`의 실제 생성자 인자 순서를 읽어 맞춘다. **서비스를 고치지 않는다.**

- [ ] **Step 3: 최소 구현을 쓴다**

`SubtaskSessionService.submit(...)`의 `session.apply(result);` **바로 다음**에 추가한다:

```java
        recordDecision(session, def, targetId, value, source);
```

그리고 클래스에 private 메서드를 추가한다:

```java
    /**
     * 이번 답변을 원장에 남긴다.
     *
     * <p><b>왜 검증 뒤에 남기는가</b>: 검증을 통과하지 못한 값을 확정으로 쌓으면, 세션은
     * 거부했는데 원장은 받아들인 상태가 된다. 원장이 실행을 여는 근거가 되므로 그 어긋남은
     * 곧 잘못된 값의 실행 경로가 된다.
     *
     * <p>13인자 조립을 여기서 다시 쓰지 않고 {@link AnswerDecisions#fromAnswer}에 맡긴다 —
     * 그 사본이 테스트에만 있던 것이 앞 작업에서 지적된 자리다.
     */
    private void recordDecision(JangnyangSubtaskSession session, JangnyangSubtaskDefinition def,
                                String subtaskId, Object rawValue, SubtaskAnswerSource source) {
        JangnyangSubtask subtask = def.byId(subtaskId);
        if (subtask == null) return;

        JangnyangSubtaskAnswer accepted = session.answers().get(subtaskId);
        // 검증기가 거부했으면 세션에 통과한 답이 없다. 원장에도 확정값을 남기지 않는다.
        if (accepted == null || !accepted.valid()) return;

        String parameterId = JangnyangLedgerWiring.parameterIdOf(subtask.answerField());
        ParameterLedger ledger = session.ledger();
        Instant now = Instant.now();

        ledger.append(AnswerDecisions.fromAnswer(
                ledger.nextDecisionId(parameterId), parameterId,
                rawValue, accepted.value(),
                source, subtask.basis(),
                new ValueSource(sourceTypeOf(source), subtaskId,
                        String.valueOf(def.version()), now),
                null, now));
    }

    /** 답변 출처를 원장의 출처 종류로 옮긴다. 없는 이름을 지어내지 않는다. */
    private static String sourceTypeOf(SubtaskAnswerSource source) {
        return switch (source) {
            case USER_DIRECT -> "user_explicit";
            case LLM_NORMALIZED -> "llm_normalized";
            case SERVER_DEFAULT -> "asset_contract";
        };
    }
```

import를 추가한다:

```java
import com.wastesim.ledger.AnswerDecisions;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.ledger.ValueSource;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import java.time.Instant;
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=SubmitRecordsDecisionTest`
Expected: PASS (4 tests)

- [ ] **Step 5: 기존 세션 테스트가 깨지지 않았는지 확인한다**

Run: `.\mvnw.cmd test -Dtest="Subtask*Test"`
Expected: 전부 통과. 실패하면 원장 기록이 기존 흐름을 바꾼 것이므로 멈추고 보고한다.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/SubtaskSessionService.java src/test/java/com/wastesim/subtask/SubmitRecordsDecisionTest.java src/test/java/com/wastesim/subtask/SubtaskTestSupport.java
git commit -m "feat(subtask): 통과한 답변을 매개변수 결정으로 원장에 남긴다"
```

---

### Task 4: 답변이 바뀌면 재계산이 돈다

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java`
- Test: `src/test/java/com/wastesim/subtask/SubmitRecalculatesTest.java`

**Interfaces:**
- Consumes: Task 2·3, `LedgerRecalculator`
- Produces: `SubtaskSessionService`가 submit 때 `onAnswerChanged`를 호출한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/SubmitRecalculatesTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.BlockingReasons;
import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구조를 바꾸는 답변이 이미 받은 답변의 전제를 무너뜨렸는지 따지는가.
 *
 * <p>지금까지는 교통을 껐다 켜도 이전 프로필 답변이 그대로 남았다. 그 답은 다른 구조에서
 * 고른 값이라 더 믿을 수 없다.
 */
class SubmitRecalculatesTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    private static final String PROFILE =
            JangnyangLedgerWiring.parameterIdOf("trafficProfileId");

    private void answer(String field, Object value) {
        sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", field), value, null);
    }

    @Test
    void 교통을_켜면_프로필이_물어야_할_자리로_드러난다() {
        sessions.start("k");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertNotNull(d);
        assertEquals(DecisionState.UNRESOLVED, d.state());
        assertEquals(BlockingReasons.REQUIRED_VALUE_UNRESOLVED, d.blockingReason());
    }

    @Test
    void 교통을_끄면_프로필은_해당_없음으로_확정된다() {
        sessions.start("k");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, "NONE");

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertNotNull(d);
        assertEquals(DecisionState.DEFAULTED, d.state());
        assertTrue(d.state().executable(), "비활성 가지가 실행을 막으면 과차단이다");
    }

    @Test
    void 교통을_껐다_켜면_이전_프로필_답변이_낡는다() {
        sessions.start("k");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);
        answer("trafficProfileId", "weekday-peak");
        assertTrue(sessions.activeSession("k").ledger().current(PROFILE).state().executable(),
                "전제 조건: 프로필을 답하면 확정된다");

        answer(JangnyangRules.TRAFFIC_MODE_FIELD, "NONE");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertFalse(d.state().executable(),
                "다른 구조에서 고른 프로필이 그대로 살아 있으면 안 된다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=SubmitRecalculatesTest`
Expected: FAIL — `교통을_켜면_프로필이_물어야_할_자리로_드러난다`에서 `d`가 `null`이다 (재계산이 돌지 않아 원장에 프로필 결정이 없다)

테스트가 `trafficMode`나 `trafficProfileId`의 서브태스크를 찾지 못해 NPE가 나면, 실제 세트의 답변 필드명을 읽어 테스트의 필드명을 맞춘다. 세트 리소스는 고치지 않는다.

- [ ] **Step 3: 최소 구현을 쓴다**

`SubtaskSessionService`에 재계산기를 필드로 둔다. 기존 필드 선언부(`private final SubtaskSessionStore store;` 아래)에 추가:

```java
    /**
     * 구조를 바꾸는 답변이 왔을 때 원장을 다시 계산한다.
     *
     * <p>배선이 고정돼 있어 인스턴스를 매번 만들 이유가 없다. 생성자에서 미등록 규칙 ID를
     * 걸러 내므로, 배선이 낡으면 서비스 조립 시점에 드러난다 — 실행 중에 조용히
     * {@code UNKNOWN}으로 떨어지는 것보다 낫다.
     */
    private final LedgerRecalculator recalculator = new LedgerRecalculator(
            JangnyangRules.registry(),
            JangnyangLedgerWiring.activeWhenByParameter(),
            JangnyangLedgerWiring.dependents());
```

`recordDecision(...)` 호출 **바로 다음**에 추가:

```java
        recalculate(session, def, targetId);
```

그리고 private 메서드 둘을 추가한다:

```java
    /**
     * 이번 답변을 기준으로 활성 구조와 종속 값을 다시 계산한다.
     *
     * <p>{@link SubtaskState}는 손대지 않는다 — {@code READY → COLLECTING} 전이가 이미
     * 허용돼 있다. 여기서 하는 일은 그 전이를 일으켜야 할 때를 알아내는 것이다.
     */
    private void recalculate(JangnyangSubtaskSession session, JangnyangSubtaskDefinition def,
                             String subtaskId) {
        JangnyangSubtask subtask = def.byId(subtaskId);
        if (subtask == null) return;
        recalculator.onAnswerChanged(session.ledger(),
                JangnyangLedgerWiring.parameterIdOf(subtask.answerField()),
                answersByField(session, def));
    }

    /**
     * 세션의 답변을 <b>답변 필드명</b>으로 펼친다.
     *
     * <p>등록된 활성 규칙은 {@code trafficMode} 같은 필드명을 본다. 서브태스크 ID를 그대로
     * 넘기면 규칙이 언제나 {@code UNKNOWN}을 돌려주고, 그러면 모든 조건부 가지가 영원히
     * 미해결로 남아 아무것도 실행할 수 없게 된다.
     */
    private static Map<String, Object> answersByField(JangnyangSubtaskSession session,
                                                      JangnyangSubtaskDefinition def) {
        Map<String, Object> byField = new LinkedHashMap<>();
        for (Map.Entry<String, JangnyangSubtaskAnswer> e : session.answers().entrySet()) {
            JangnyangSubtask s = def.byId(e.getKey());
            if (s == null || !e.getValue().valid()) continue;
            byField.put(s.answerField(), e.getValue().value());
        }
        return byField;
    }
```

import를 추가한다:

```java
import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.LedgerRecalculator;
import java.util.LinkedHashMap;
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=SubmitRecalculatesTest`
Expected: PASS (3 tests)

- [ ] **Step 5: 기존 세션 테스트가 깨지지 않았는지 확인한다**

Run: `.\mvnw.cmd test -Dtest="Subtask*Test"`
Expected: 전부 통과.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/SubtaskSessionService.java src/test/java/com/wastesim/subtask/SubmitRecalculatesTest.java
git commit -m "feat(subtask): 구조를 바꾸는 답변이 오면 종속 값을 다시 계산한다"
```

---

### Task 5: 조립은 막지 않고 경고만 싣는다

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java`
- Test: `src/test/java/com/wastesim/subtask/BuildWarnsNotBlocksTest.java`

**Interfaces:**
- Consumes: Task 3·4
- Produces: `SubtaskSessionService.BuildStep`에 네 번째 컴포넌트 `List<String> ledgerWarnings`, 접근자 `ledgerWarnings()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/BuildWarnsNotBlocksTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 단계적 강제의 경계를 고정한다.
 *
 * <p>원장과 기존 checker는 기준이 다르다 — 원장은 {@code BasisKind.NONE}을 막지만 checker는
 * 그 필드를 {@code required=false}로 통과시킬 수 있다. 조립부터 강제하면 지금 통과하던
 * 구성이 갑자기 막히고, 그것이 진짜 결함인지 두 기준의 차이인지 구분할 데이터가 없다.
 */
class BuildWarnsNotBlocksTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    @Test
    void 원장이_막아도_조립은_성공한다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        // 교통을 켜면 프로필이 미해결로 드러난다 — 원장은 막지만 세트는 답을 다 받았다.
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertTrue(build.ok(), "조립 단계에서 원장이 막으면 단계적 강제가 아니다: "
                + build.message());
    }

    @Test
    void 막힌_사유는_경고로_실린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertFalse(build.ledgerWarnings().isEmpty(),
                "막을 이유가 있는데 아무 말도 하지 않으면 데이터가 모이지 않는다");
        assertTrue(build.ledgerWarnings().stream().anyMatch(w -> w.contains("trafficProfileId")),
                "무엇이 막는지 알 수 없는 경고는 쓸모가 없다: " + build.ledgerWarnings());
    }

    @Test
    void 막을_것이_없으면_경고도_없다() {
        SubtaskTestSupport.answerEverything(sessions, "k");

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertTrue(build.ok(), build.message());
        assertEquals(java.util.List.of(), build.ledgerWarnings(),
                "정상 구성에 경고가 붙으면 경고가 잡음이 된다");
    }
}
```

`SubtaskTestSupport`에 헬퍼를 추가한다 (같은 커밋):

```java
    /**
     * 이 세션의 모든 수집 질문에 유효한 답을 넣어 READY까지 보낸다.
     *
     * <p>질문을 하나씩 나열하지 않는 이유는 세트 버전이 바뀌면 그 목록이 통째로 낡기
     * 때문이다. 세션이 "다음 질문"이라고 알려 주는 것을 따라간다.
     */
    static void answerEverything(SubtaskSessionService sessions, String sessionKey) {
        sessions.start(sessionKey);
        for (int guard = 0; guard < 200; guard++) {
            SubtaskSessionService.Step step = sessions.currentStep(sessionKey);
            JangnyangSubtask next = step.subtask();
            if (next == null) return;
            sessions.submit(sessionKey, next.id(), sampleAnswerFor(next), null);
        }
        throw new IllegalStateException("200번 답해도 질문이 끝나지 않았다 — 재질문 고리를 의심하라");
    }

    /** 이 서브태스크가 받아들일 만한 값 하나. 허용 범위·선택지에서 고른다. */
    static Object sampleAnswerFor(JangnyangSubtask s) {
        AllowedRange r = s.allowedRange();
        if (r != null && r.values() != null && !r.values().isEmpty()) return r.values().get(0);
        return switch (s.answerType()) {
            case INTEGER -> r != null && r.min() != null ? r.min().intValue() : 1;
            case NUMBER -> r != null && r.min() != null ? r.min() : 1.0;
            case BOOLEAN -> Boolean.TRUE;
            default -> "해당 없음";
        };
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=BuildWarnsNotBlocksTest`
Expected: 컴파일 실패 — `BuildStep.ledgerWarnings()`가 없다

`sampleAnswerFor`가 `AnswerType`·`AllowedRange`의 실제 멤버와 맞지 않아 컴파일되지 않으면, 두 타입을 읽어 맞춘다. **그 타입들을 고치지 않는다.**

- [ ] **Step 3: 최소 구현을 쓴다**

`BuildStep` 레코드에 컴포넌트를 추가하고 팩토리 셋을 맞춘다:

```java
    /**
     * @param ledgerWarnings 원장이 막을 이유로 본 것들. <b>조립을 막지는 않는다</b> —
     *                       원장과 기존 checker는 기준이 달라, 강제를 넓히기 전에 그
     *                       차이가 실제로 얼마나 나는지 볼 데이터가 먼저 필요하다
     */
    public record BuildStep(JangnyangScenarioSpec spec,
                            JangnyangScenarioBuilder.BuildOutcome outcome,
                            String rejection,
                            List<String> ledgerWarnings) {

        public BuildStep {
            ledgerWarnings = ledgerWarnings == null ? List.of() : List.copyOf(ledgerWarnings);
        }

        static BuildStep built(JangnyangScenarioSpec spec, List<String> warnings) {
            return new BuildStep(spec, null, null, warnings);
        }
        static BuildStep failed(JangnyangScenarioBuilder.BuildOutcome o) {
            return new BuildStep(null, o, null, List.of());
        }
        static BuildStep rejected(String reason) {
            return new BuildStep(null, null, reason, List.of());
        }
```

`ok()`·`message()`는 그대로 둔다.

`build(...)`에서 성공 경로를 바꾼다. `session.transitionTo(SubtaskState.BUILT);` 다음의 `return BuildStep.built(outcome.spec());`를:

```java
        return BuildStep.built(outcome.spec(), ledgerWarningsOf(session));
```

그리고 private 메서드를 추가한다:

```java
    /**
     * 원장이 막을 이유로 보는 것들을 사람이 읽는 문장으로.
     *
     * <p>조립을 막지 않고 실어 보내기만 한다. 이 경고가 상시로 뜬다면 원장과 기존 checker의
     * 기준 차이가 크다는 뜻이고, 그때는 강제를 넓히기 전에 그 차이를 먼저 읽어야 한다.
     */
    private static List<String> ledgerWarningsOf(JangnyangSubtaskSession session) {
        return session.ledger().blocking().stream()
                .map(d -> d.parameterId() + ": " + d.blockingReason())
                .toList();
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=BuildWarnsNotBlocksTest`
Expected: PASS (3 tests)

- [ ] **Step 5: 기존 build 호출부가 깨지지 않았는지 확인한다**

Run: `.\mvnw.cmd test -Dtest="SubtaskChatFlowTest+SubtaskChatFlowV5Test+SubtaskSessionStoreTest+SubtaskNormalizationTest"`
Expected: 전부 통과. `BuildStep`을 직접 생성하는 곳이 있어 컴파일이 깨지면 팩토리로 바꾼다.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/SubtaskSessionService.java src/test/java/com/wastesim/subtask/BuildWarnsNotBlocksTest.java src/test/java/com/wastesim/subtask/SubtaskTestSupport.java
git commit -m "feat(subtask): 조립은 막지 않고 원장이 막을 이유를 실어 보낸다"
```

---

### Task 6: 실행 승인이 원장 차단을 강제한다

**Files:**
- Create: `src/main/java/com/wastesim/subtask/RunApproval.java`
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java`
- Test: `src/test/java/com/wastesim/subtask/ApproveRunBlocksTest.java`

**Interfaces:**
- Consumes: Task 3·4·5
- Produces: `RunApproval(JangnyangScenarioSpec spec, List<String> blocks)` · `approved()` · `message()`; `SubtaskSessionService.approveRunChecked(String)` → `RunApproval`. `approveRun(String)`의 시그니처와 반환 계약은 **그대로**.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/ApproveRunBlocksTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미해결 필수값이 있으면 실행 패키지를 발행하지 않는다 — 앞 스펙이 약속하고 지금까지
 * 지켜지지 않던 자리다.
 *
 * <p>기존 반환 계약({@code null} 또는 spec)을 바꾸지 않는다. 차단은 BUILT가 아닐 때와
 * 같은 {@code null}이고, 사유는 {@code approveRunChecked}가 들고 나온다.
 */
class ApproveRunBlocksTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    private void buildWithUnresolvedTraffic() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);
        assertTrue(sessions.build("k").ok(), "전제 조건: 조립은 성공해야 한다");
    }

    @Test
    void 원장이_막으면_실행이_열리지_않는다() {
        buildWithUnresolvedTraffic();
        assertNull(sessions.approveRun("k"),
                "미해결 필수값이 있는데 실행 설정이 나가면 fail-closed가 무너진다");
    }

    @Test
    void 차단_사유가_남는다() {
        buildWithUnresolvedTraffic();
        RunApproval approval = sessions.approveRunChecked("k");

        assertFalse(approval.approved());
        assertTrue(approval.blocks().stream().anyMatch(b -> b.contains("trafficProfileId")),
                "무엇이 막는지 알 수 없으면 사용자가 고칠 수 없다: " + approval.blocks());
    }

    @Test
    void 막힌_뒤에도_세션은_실행_상태로_넘어가지_않는다() {
        buildWithUnresolvedTraffic();
        sessions.approveRun("k");
        assertEquals(SubtaskState.BUILT, sessions.activeSession("k").state(),
                "차단했는데 RUNNING으로 올라가면 다음 호출이 실행을 믿는다");
    }

    @Test
    void 정상_구성은_실행이_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        assertTrue(sessions.build("k").ok());

        assertNotNull(sessions.approveRun("k"),
                "막을 이유가 없는 구성을 막으면 과차단이다");
    }

    @Test
    void BUILT가_아니면_지금까지처럼_null이다() {
        sessions.start("k");
        assertNull(sessions.approveRun("k"), "기존 계약이 바뀌면 호출부가 조용히 깨진다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=ApproveRunBlocksTest`
Expected: 컴파일 실패 — `RunApproval`과 `approveRunChecked`가 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/wastesim/subtask/RunApproval.java`:

```java
package com.wastesim.subtask;

import java.util.List;

/**
 * 실행 승인의 결과. spec 아니면 차단 사유를 들고 나온다.
 *
 * <p><b>왜 {@code null} 대신 이것을 만드는가</b>: 기존 {@code approveRun}은 차단을
 * {@code null}로만 말할 수 있어 사유를 잃는다. 사용자는 왜 실행이 열리지 않는지 모른 채
 * 같은 요청을 반복하게 된다. 반환 계약을 바꾸면 호출부가 조용히 깨지므로, 계약은 두고
 * 사유를 읽을 길만 따로 낸다.
 *
 * @param blocks 막은 이유 전부. 첫 하나에서 멈추면 고치고 다시 시도하기를 반복해야 한다
 */
public record RunApproval(JangnyangScenarioSpec spec, List<String> blocks) {

    public RunApproval {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    static RunApproval approved(JangnyangScenarioSpec spec) {
        return new RunApproval(spec, List.of());
    }

    static RunApproval blocked(List<String> blocks) {
        return new RunApproval(null, blocks);
    }

    public boolean approved() {
        return spec != null;
    }

    /** 사람이 읽는 차단 사유. 승인됐으면 빈 문자열. */
    public String message() {
        if (approved()) return "";
        StringBuilder sb = new StringBuilder("아직 실행할 수 없습니다:\n");
        for (String b : blocks) sb.append("- ").append(b).append('\n');
        return sb.toString();
    }
}
```

`SubtaskSessionService.approveRun(...)`을 통째로 바꾼다:

```java
    /**
     * 실행 승인. BUILT가 아니면 거부한다 — 조립을 거치지 않은 세션에는 실행할 설정이 없다
     * (FR-129·UT-317).
     *
     * <p>반환 계약은 그대로다 — 막히면 {@code null}. 사유가 필요하면
     * {@link #approveRunChecked(String)}을 쓴다.
     */
    public JangnyangScenarioSpec approveRun(String sessionKey) {
        return approveRunChecked(sessionKey).spec();
    }

    /**
     * 실행 승인과 그 사유.
     *
     * <p><b>여기서만 원장을 강제한다.</b> 조립 단계는 경고만 싣는다 — 원장과 기존 checker는
     * 기준이 달라, 조립부터 막으면 지금 통과하던 구성이 갑자기 막히고 그것이 진짜 결함인지
     * 두 기준의 차이인지 구분할 데이터가 없다. 실행만 막아도 "미해결 필수값이 있으면 실행
     * 패키지를 발행하지 않는다"는 요구는 달성된다.
     */
    public RunApproval approveRunChecked(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().canRun()) {
            return RunApproval.blocked(List.of("아직 실행할 수 있는 상태가 아닙니다."));
        }

        List<String> blocks = ledgerWarningsOf(session);
        if (!blocks.isEmpty()) {
            // 상태를 올리지 않는다 — BUILT에 머물러야 사용자가 답을 고쳐 다시 시도할 수 있다.
            return RunApproval.blocked(blocks);
        }

        session.transitionTo(SubtaskState.RUNNING);
        store.save(session);
        return RunApproval.approved(session.spec());
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=ApproveRunBlocksTest`
Expected: PASS (5 tests)

- [ ] **Step 5: 기존 approveRun 호출부 넷이 그대로인지 확인한다**

Run: `.\mvnw.cmd test -Dtest="SubtaskNormalizationTest+SubtaskSessionStoreTest"`
Expected: 전부 통과. 특히 `SubtaskSessionStoreTest:134`의 `assertNotNull(sessions.approveRun("k"), "BUILT 이후에만 실행이 열린다")`가 통과해야 한다 — 실패하면 원장이 정상 구성을 막고 있다는 뜻이므로 멈추고 보고한다.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/RunApproval.java src/main/java/com/wastesim/subtask/SubtaskSessionService.java src/test/java/com/wastesim/subtask/ApproveRunBlocksTest.java
git commit -m "feat(subtask): 실행 승인이 원장의 차단 상태를 강제한다"
```

---

### Task 7: 실행 승인이 역검증을 강제한다

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/SubtaskSessionService.java`
- Test: `src/test/java/com/wastesim/subtask/ApproveRunBackVerifiesTest.java`

**Interfaces:**
- Consumes: Task 2의 `JangnyangLedgerWiring.fieldToParameterId()`, Task 6의 `approveRunChecked`, `ConfigBackVerifier`
- Produces: 없음 (`approveRunChecked`의 동작만 늘어난다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/ApproveRunBackVerifiesTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ValueSource;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 검증된 계획이 잘못된 실행 설정으로 바뀌는 오류는 계획 검증으로 잡히지 않는다 —
 * 계획 쪽은 전부 통과하기 때문이다.
 */
class ApproveRunBackVerifiesTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    @Test
    void 정상_구성은_역검증을_통과한다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        assertTrue(sessions.build("k").ok());

        RunApproval approval = sessions.approveRunChecked("k");
        assertTrue(approval.approved(),
                "정상 구성이 역검증에서 막히면 과차단이다: " + approval.message());
    }

    @Test
    void 원장과_다른_값이_설정에_들어가면_막는다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        assertTrue(sessions.build("k").ok());

        // 원장에만 다른 값을 심는다. 설정은 그대로이므로 둘이 어긋난다 —
        // 빌더가 값을 잘못 옮긴 상황을 이 방향에서 재현한다.
        var ledger = sessions.activeSession("k").ledger();
        String days = JangnyangLedgerWiring.parameterIdOf("days");
        ledger.append(new ParameterDecision(
                ledger.nextDecisionId(days), days, DecisionState.CONFIRMED,
                9999, null, 9999, null,
                new ValueSource("user_explicit", "ST-TAMPER", null, Instant.now()),
                null, List.of(), null, null, Instant.now()));

        RunApproval approval = sessions.approveRunChecked("k");

        assertFalse(approval.approved(), "역검증이 불일치를 통과시켰다");
        assertTrue(approval.blocks().stream().anyMatch(b -> b.contains(days)),
                "무엇이 어긋났는지 알 수 없다: " + approval.blocks());
    }

    @Test
    void 변환되는_필드는_역검증이_막지_않는다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        assertTrue(sessions.build("k").ok());

        RunApproval approval = sessions.approveRunChecked("k");

        assertTrue(approval.approved(),
                "변환되는 필드를 등가 비교하면 정상 구성이 전부 막힌다: " + approval.message());
        for (String transformed : JangnyangLedgerWiring.TRANSFORMED_FIELDS.keySet()) {
            assertFalse(approval.blocks().stream().anyMatch(b -> b.contains(transformed)),
                    transformed + "은 대조 대상이 아닌데 차단 사유에 나왔다");
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\mvnw.cmd test -Dtest=ApproveRunBackVerifiesTest`
Expected: FAIL — `원장과_다른_값이_설정에_들어가면_막는다`가 통과해 버린다 (역검증이 호출되지 않아 승인된다)

- [ ] **Step 3: 최소 구현을 쓴다**

`SubtaskSessionService`에 역검증기를 필드로 둔다 (`recalculator` 옆):

```java
    /**
     * 조립된 설정을 원장과 대조한다. 계획 검증이 통과한 뒤 변환 자체를 의심하는 유일한
     * 자리다.
     */
    private final ConfigBackVerifier backVerifier = new ConfigBackVerifier();
```

`approveRunChecked(...)`에서 원장 차단 검사 **다음**에 역검증을 넣는다:

```java
        List<String> blocks = ledgerWarningsOf(session);
        if (!blocks.isEmpty()) {
            return RunApproval.blocked(blocks);
        }

        // 원장이 열어 준 뒤에 조립 결과를 되짚는다. 순서가 반대면 미해결 값 때문에 생긴
        // 불일치를 "변환 오류"로 잘못 보고하게 된다.
        BackVerificationResult back = backVerifier.verify(
                session.spec().toSimulationConfig(), session.ledger(),
                JangnyangLedgerWiring.fieldToParameterId());
        if (!back.passed()) {
            return RunApproval.blocked(back.blocks());
        }
```

import를 추가한다:

```java
import com.wastesim.ledger.verify.BackVerificationResult;
import com.wastesim.ledger.verify.ConfigBackVerifier;
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=ApproveRunBackVerifiesTest`
Expected: PASS (3 tests)

만약 `정상_구성은_역검증을_통과한다`가 실패하면 `approval.message()`가 어느 필드에서 어긋났는지 알려 준다. 그 필드가 빌더에서 변환되는 자리이면 Task 2의 `TRANSFORMED_FIELDS`에 사유와 함께 추가한다. **역검증기나 빌더를 고치지 않는다.**

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/wastesim/subtask/SubtaskSessionService.java src/test/java/com/wastesim/subtask/ApproveRunBackVerifiesTest.java
git commit -m "feat(subtask): 실행 직전에 조립 결과를 원장과 대조한다"
```

---

### Task 8: 전체 흐름을 오류 주입과 과차단으로 짝지어 지킨다

**Files:**
- Create: `src/test/java/com/wastesim/subtask/LedgerIntegrationFlowTest.java`

**Interfaces:**
- Consumes: Task 1~7 전부
- Produces: 없음 (테스트만)

- [ ] **Step 1: 통합 테스트를 쓴다**

`src/test/java/com/wastesim/subtask/LedgerIntegrationFlowTest.java`:

```java
package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 부품이 아니라 <b>흐름</b>을 지킨다. 오류 주입과 과차단을 짝으로 둔다 — 차단만 늘리면
 * "필수값 누락 0"과 "실행 성공률"이 동시에 올라가는 착시가 생긴다.
 */
class LedgerIntegrationFlowTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    // ---- 과차단: 정상 흐름은 끝까지 간다 ----

    @Test
    void 전부_답한_구성은_조립도_실행도_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");

        SubtaskSessionService.BuildStep build = sessions.build("k");
        assertTrue(build.ok(), build.message());
        assertEquals(java.util.List.of(), build.ledgerWarnings());

        RunApproval approval = sessions.approveRunChecked("k");
        assertTrue(approval.approved(), approval.message());
    }

    @Test
    void 교통을_끈_구성도_끝까지_간다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);

        assertTrue(sessions.build("k").ok());
        assertTrue(sessions.approveRunChecked("k").approved(),
                "비활성 가지가 실행을 막으면 과차단이다");
    }

    // ---- 오류 주입: 알려진 구멍은 실행 전에 막힌다 ----

    @Test
    void 교통을_켜고_프로필을_답하지_않으면_실행이_막힌다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        assertTrue(sessions.build("k").ok(), "조립은 열려 있어야 한다");
        assertNull(sessions.approveRun("k"), "실행은 막혀야 한다");
    }

    @Test
    void 프로필을_답하면_다시_실행이_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);
        assertNull(sessions.approveRun("k"));

        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", "trafficProfileId"),
                SubtaskTestSupport.sampleAnswerFor(
                        sessions.definitionOf(sessions.activeSession("k"))
                                .byId(SubtaskTestSupport.idOfField(sessions, "k", "trafficProfileId"))),
                null);
        assertTrue(sessions.build("k").ok());

        RunApproval approval = sessions.approveRunChecked("k");
        assertTrue(approval.approved(),
                "막힌 자리를 채웠는데도 열리지 않으면 재계산이 갱신되지 않은 것이다: "
                        + approval.message());
    }

    @Test
    void 답을_고치면_원장에_이력이_남는다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        String daysId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", daysId, 3, null);
        sessions.submit("k", daysId, 5, null);

        var history = sessions.activeSession("k").ledger()
                .history(JangnyangLedgerWiring.parameterIdOf("days"));

        assertTrue(history.size() >= 2, "값을 고친 이력이 남지 않았다");
    }
}
```

- [ ] **Step 2: 실행해 통과를 확인한다**

Run: `.\mvnw.cmd test -Dtest=LedgerIntegrationFlowTest`
Expected: PASS (5 tests)

`프로필을_답하면_다시_실행이_열린다`가 실패하면 재계산이 프로필 답변 뒤에 갱신되지 않는다는 뜻이다. Task 4의 `recalculate` 호출 위치를 본다 — `recordDecision` **다음**이어야 한다.

- [ ] **Step 3: 전체 스위트를 돌린다**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS. 실패가 `com.wastesim.subtask` 밖이면 이 계획과 무관할 가능성이 높다 — 고치지 말고 어느 패키지인지 보고한다.

- [ ] **Step 4: 커밋한다**

```bash
git add src/test/java/com/wastesim/subtask/LedgerIntegrationFlowTest.java
git commit -m "test(subtask): 원장을 낀 전체 흐름을 오류 주입과 과차단으로 지킨다"
```

---

## 이 계획이 만들지 않는 것

- **MCP 실제 도구 호출** — `CandidateAdmission`은 승인 쪽만 구현돼 있고 브로커·호출·타임아웃은 없다. 스펙이 범위 밖으로 명시했다.
- **변환되는 5개 필드의 값 대조** — 변환 규칙을 등록해 기대값을 다시 계산해야 하는 일이다. 지금은 제외를 **선언**해 역검증이 무엇을 보지 않는지 드러나게만 한다.
- **조립 단계의 차단** — 단계적 강제의 다음 단계다. 경고 데이터를 먼저 본다.
- **`JangnyangCompletenessChecker` 대체** — 원장은 그것이 보지 않는 것을 볼 뿐 대체하지 않는다.

## 자기 점검 결과

- **스펙 커버리지**: 결정 1 → Task 1, 결정 2 → Task 3·4, 결정 3 → Task 5(조립 경고)·6(실행 차단), 결정 4 → Task 2·7, 테스트 표 7행 → Task 2·5·6·7·8에 각각 대응. 빠진 절 없음.
- **타입 일관성**: `BuildStep`은 Task 5에서 4컴포넌트가 되고 Task 8이 `ledgerWarnings()`로 읽는다. `RunApproval`은 Task 6에서 정의되고 Task 7·8이 `approved()`·`blocks()`·`message()`로 읽는다. `JangnyangLedgerWiring`의 공개 멤버 6개가 Task 3·4·7·8에서 같은 이름으로 쓰인다. `ParameterDecision` 13인자는 Task 7 테스트 한 곳에서만 직접 조립하며 순서가 기존 정의와 같다.
- **placeholder**: 없음. 모든 코드 단계에 실제 코드가 있다.
- **알려진 위험**: `SubtaskTestSupport.sampleAnswerFor`가 `AnswerType`·`AllowedRange`의 실제 멤버와 어긋날 수 있다 — Task 5 Step 2에 대응을 적었다. `answerEverything`이 세트를 끝까지 답하지 못하면 200회 가드가 터지며 재질문 고리를 알려 준다.
