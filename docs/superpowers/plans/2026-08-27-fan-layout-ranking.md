# 듀얼 팬 배치 조합 랭킹 도구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 라즈베리파이 40 mm 듀얼 팬의 위치·방향 조합 60가지를 경험적 냉각점수로 순위 매기는 MCP 도구 `rank_fan_layouts`를 추가한다.

**Architecture:** 신규 패키지 `com.wastesim.edge.layout`에 점수 모델을 두고, 기존 열 시뮬레이션 스택(`ThermalSimulator` / `HeatsinkThermalModel` / `FanArraySpec`)과 **컴파일 의존성 수준에서 격리**한다. 도구는 `McpToolProvider`를 구현해 스프링이 자동 등록하고, 채팅은 `EdgeToolSelector`의 신규 `FAN_LAYOUT` 패턴으로 라우팅한다. 임의 앵커(82 ℃)에 기댄 예상 온도는 응답의 `advisory` 블록으로 분리한다.

**Tech Stack:** Java 17 records/enums, Spring Boot, Jackson `JsonNode`, JUnit 5, Maven (`./mvnw.cmd`), 프런트엔드는 바닐라 JS (`static/js/edge.js`).

**Spec:** [`docs/superpowers/specs/2026-08-27-fan-layout-ranking-design.md`](../specs/2026-08-27-fan-layout-ranking-design.md)

## Global Constraints

- **작업 디렉터리는 `C:\Dev\MCP\waste-sim-spring`이다.** 이 문서의 모든 경로는 그 디렉터리 기준이다.
- **테스트 실행:** `./mvnw.cmd -q test -Dtest=<TestClass>`. 전체는 `./mvnw.cmd -q test`.
- **격리 규칙 (D-43):** `com.wastesim.edge.layout` 패키지의 어떤 파일도 `ThermalSimulator`·`HeatsinkThermalModel`·`ThermalParams`·`ThermalRun`을 import 하지 않는다. `FanArraySpec`은 `SourceStatus` enum 값만 쓴다.
- **주석은 한국어로,** 기존 `edge` 패키지처럼 "왜 이렇게 했는가"를 적는다. 계수에는 출처(엑셀 `가정` 시트, 2026-08-27)를 단다.
- **fail-closed:** 모르는 값·범위 밖 값은 실행하지 않고 `ToolResult.rejected(...)`로 거부한다.
- **부동소수점 비교 허용 오차:** 골든 값 단언은 `1e-9`, 동률 판정도 `1e-9`.
- **점수식 상수 (엑셀과 일치해야 함):** `BARE_PEAK_ANCHOR_C=82.0`, `SCORE_TO_DELTA_C=27.0`, `MEAN_OFFSET_C=5.2`, `SPREAD_BASE=3.0`, `SPREAD_SLOPE=10.0`, `SAME_DIRECTION_SPREAD_PENALTY=2.0`, `SCORE_MIN=0.25`, `SCORE_MAX=1.15`, `SHORT_CIRCUIT_PENALTY=-0.12`, `NATURAL_CONVECTION_BONUS=0.15`, `AGAINST_CONVECTION_PENALTY=-0.10`, `INTAKE_PAIR_FACTOR=0.78`, `EXHAUST_PAIR_FACTOR=0.82`, `THROUGH_FLOW_FACTOR=1.0`, `RISK_LOW_THRESHOLD=0.95`, `RISK_MEDIUM_THRESHOLD=0.78`.
- **항상 반환하는 경고 3종:** `FAN_SPEC_NOT_VERIFIED`, `ADVISORY_TEMP_ANCHORED_ESTIMATE`, `ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR`.

---

## File Structure

**신규 (`src/main/java/com/wastesim/edge/layout/`)**

| 파일 | 책임 |
|---|---|
| `FanMountPosition.java` | 장착 위치 6곳 — 한글 라벨·높이 코드·측면·위치효율, `parse()` |
| `FanFlowRole.java` | 흡기/배기, `parse()` |
| `FanLayoutCandidate.java` | 조합 하나(ID + 위치쌍 + 방향쌍) |
| `FanLayoutScore.java` | 한 조합의 점수·해석·advisory 온도 |
| `FanLayoutScoreModel.java` | 계수 상수 + `score()` + `enumerateAll()` |
| `FanLayoutRanking.java` | 순위 목록·경고·동률 규칙 + `Map` 직렬화 |
| `RankFanLayoutsTool.java` | MCP 입력 검증·실행·응답 조립 |

**신규 (`src/test/java/com/wastesim/edge/layout/`)**

| 파일 | 책임 |
|---|---|
| `FanLayoutScoreModelTest.java` | 점수식 골든 회귀·경계·대칭성 |
| `RankFanLayoutsToolTest.java` | 도구 계약·입력 검증·순위·경고 |
| `FanLayoutIsolationTest.java` | 격리 규칙(D-43)을 소스 스캔으로 고정 |

**수정**

| 파일 | 변경 |
|---|---|
| `service/EdgeToolSelector.java` | `TOOL_LAYOUT` 상수, `FAN_LAYOUT` 패턴, 검사 순서 |
| `model/ChatMessage.java` | `EDGE_LAYOUT` 타입, `edgeLayout` 필드 |
| `edge/EdgeChatFormatter.java` | `fanLayout(Map)` 포매터 |
| `controller/ChatController.java` | `TOOL_LAYOUT` 분기 + `runEdgeFanLayout()` |
| `resources/static/js/edge.js` | `EDGE_LAYOUT` 렌더러 + 사이드바 칩 |
| `test/.../EdgeChatRoutingTest.java` | 라우팅 회귀 8케이스 |
| `test/.../EdgeMcpToolsTest.java` | 도구 5개 등록 확인 |
| `docs/specifications/docs_..._v1_12.md` | FR-115~118, UT-268~281, D-43 |
| `docs/reference/FAN_RPM_SWEEP_DESIGN.md` | §12 상호 참조 |

---

## Task 1: 위치·방향 enum

**Files:**
- Create: `src/main/java/com/wastesim/edge/layout/FanMountPosition.java`
- Create: `src/main/java/com/wastesim/edge/layout/FanFlowRole.java`
- Test: `src/test/java/com/wastesim/edge/layout/FanLayoutScoreModelTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `enum FanMountPosition { BOTTOM, TOP, LEFT_BOTTOM, LEFT_TOP, RIGHT_BOTTOM, RIGHT_TOP }`
    - `String koLabel()`, `int level()`, `Side side()`, `double efficiency()`, `String wire()`
    - `static FanMountPosition parse(String)` — 못 읽으면 `null`
    - 중첩 `enum Side { CENTER, LEFT, RIGHT }`
  - `enum FanFlowRole { INTAKE, EXHAUST }`
    - `String koLabel()`, `String wire()`, `static FanFlowRole parse(String)` — 못 읽으면 `null`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/edge/layout/FanLayoutScoreModelTest.java`:

```java
package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 점수 모델의 골든 회귀 — 엑셀 dual_fan_all_layouts_preliminary.xlsx와
 * 숫자가 어긋나면 여기서 깨진다. 이 도구의 유일한 근거가 그 시트이므로,
 * 시트와의 일치가 곧 정확성 기준이다.
 */
class FanLayoutScoreModelTest {

    @Test
    @DisplayName("장착 위치 6곳의 높이·측면·위치효율이 엑셀 가정 시트와 같다")
    void positionsMatchAssumptionSheet() {
        assertEquals(6, FanMountPosition.values().length);

        assertEquals(0, FanMountPosition.BOTTOM.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.BOTTOM.side());
        assertEquals(0.95, FanMountPosition.BOTTOM.efficiency(), 1e-9);
        assertEquals("하단", FanMountPosition.BOTTOM.koLabel());

        assertEquals(2, FanMountPosition.TOP.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.TOP.side());
        assertEquals(0.90, FanMountPosition.TOP.efficiency(), 1e-9);

        assertEquals(0.78, FanMountPosition.LEFT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.LEFT, FanMountPosition.LEFT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.LEFT_TOP.efficiency(), 1e-9);
        assertEquals(0.78, FanMountPosition.RIGHT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.RIGHT, FanMountPosition.RIGHT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.RIGHT_TOP.efficiency(), 1e-9);
    }

    @Test
    @DisplayName("위치·방향은 영문 키와 한글 라벨을 모두 받고, 모르는 값은 null이다")
    void parseAcceptsBothNotations() {
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("bottom"));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("  BOTTOM  "));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("하단"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("left_top"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("좌측 상단"));
        assertNull(FanMountPosition.parse("뒷면"));
        assertNull(FanMountPosition.parse(null));

        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("intake"));
        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("흡기"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("exhaust"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("배기"));
        assertNull(FanFlowRole.parse("순환"));
        assertNull(FanFlowRole.parse(null));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: 컴파일 실패 — `FanMountPosition` / `FanFlowRole` 심볼을 찾을 수 없음

- [ ] **Step 3: enum 두 개를 구현한다**

`src/main/java/com/wastesim/edge/layout/FanMountPosition.java`:

```java
package com.wastesim.edge.layout;

/**
 * 40 mm 팬을 달 수 있는 함체 장착 위치 6곳.
 *
 * <p>{@code efficiency}는 통풍구 접근성과 예상 유로를 반영한 <b>임시 계수</b>다
 * (출처: dual_fan_all_layouts_preliminary.xlsx "가정" 시트, 2026-08-27).
 * CFD도 실측도 아니며, 배치 후보를 줄이는 상대 비교에만 쓴다.
 *
 * <p>{@code level}은 높이 코드다 — 하단 0, 상단 2. 자연대류가 아래에서 위로 흐르므로
 * 흡기가 배기보다 낮은지(같은 방향인지 거스르는지)를 이 값으로 판정한다.
 *
 * <p>{@code side}는 좌·우·중앙이다. 흡기와 배기가 <b>같은 측면</b>에 있으면 들어온
 * 공기가 보드를 지나지 않고 곧장 빠져나가는 단락(short circuit)이 생긴다.
 */
public enum FanMountPosition {

    BOTTOM("하단", 0, Side.CENTER, 0.95),
    TOP("상단", 2, Side.CENTER, 0.90),
    LEFT_BOTTOM("좌측 하단", 0, Side.LEFT, 0.78),
    LEFT_TOP("좌측 상단", 2, Side.LEFT, 0.82),
    RIGHT_BOTTOM("우측 하단", 0, Side.RIGHT, 0.78),
    RIGHT_TOP("우측 상단", 2, Side.RIGHT, 0.82);

    /** 함체에서 팬이 붙은 면. 단락 판정에 쓰이므로 CENTER를 따로 둔다. */
    public enum Side { CENTER, LEFT, RIGHT }

    private final String koLabel;
    private final int level;
    private final Side side;
    private final double efficiency;

    FanMountPosition(String koLabel, int level, Side side, double efficiency) {
        this.koLabel = koLabel;
        this.level = level;
        this.side = side;
        this.efficiency = efficiency;
    }

    public String koLabel() { return koLabel; }
    public int level() { return level; }
    public Side side() { return side; }
    public double efficiency() { return efficiency; }

    /** MCP 응답에 쓰는 소문자 키. */
    public String wire() { return name().toLowerCase(); }

    /**
     * 영문 키("bottom")와 한글 라벨("하단")을 모두 받는다 — 이 도구는 MCP로도,
     * 한국어 채팅으로도 불린다. 읽을 수 없으면 <b>추측하지 않고 null</b>을 돌려
     * 호출측이 거부하게 한다(fail-closed).
     */
    public static FanMountPosition parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (FanMountPosition p : values()) {
            if (p.koLabel.equals(s)) return p;
        }
        String key = s.toUpperCase().replace("-", "_").replace(" ", "_");
        for (FanMountPosition p : values()) {
            if (p.name().equals(key)) return p;
        }
        return null;
    }
}
```

`src/main/java/com/wastesim/edge/layout/FanFlowRole.java`:

```java
package com.wastesim.edge.layout;

/**
 * 팬 하나가 맡는 역할 — 함체 안으로 불어넣는가(흡기), 밖으로 빼는가(배기).
 *
 * <p>기존 {@code FanArraySpec.FlowDirection}과 일부러 분리했다. 그쪽은 송풍 방향
 * (아래로/위로/수평)을 담는 열 시뮬레이션용 메타데이터고, 이쪽은 함체 기류의
 * 입·출구 역할이다. 같은 enum으로 묶으면 이 도구의 임시 계수가 열 스택 쪽 타입에
 * 얹혀 흘러 들어갈 통로가 생긴다(설계 §3.1 격리 규칙).
 */
public enum FanFlowRole {

    INTAKE("흡기"),
    EXHAUST("배기");

    private final String koLabel;

    FanFlowRole(String koLabel) { this.koLabel = koLabel; }

    public String koLabel() { return koLabel; }

    public String wire() { return name().toLowerCase(); }

    /** 읽을 수 없으면 null — 호출측이 거부한다(fail-closed). */
    public static FanFlowRole parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        for (FanFlowRole r : values()) {
            if (r.koLabel.equals(s) || r.name().equalsIgnoreCase(s)) return r;
        }
        return null;
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/edge/layout src/test/java/com/wastesim/edge/layout
git commit -m "feat(edge): 팬 장착 위치·기류 역할 enum 추가 (FR-115)"
```

---

## Task 2: 조합 전수 열거

**Files:**
- Create: `src/main/java/com/wastesim/edge/layout/FanLayoutCandidate.java`
- Create: `src/main/java/com/wastesim/edge/layout/FanLayoutScoreModel.java` (열거 부분만)
- Modify: `src/test/java/com/wastesim/edge/layout/FanLayoutScoreModelTest.java`

**Interfaces:**
- Consumes: `FanMountPosition`, `FanFlowRole` (Task 1)
- Produces:
  - `record FanLayoutCandidate(String id, FanMountPosition position1, FanFlowRole flow1, FanMountPosition position2, FanFlowRole flow2)`
  - `static List<FanLayoutCandidate> FanLayoutScoreModel.enumerateAll(List<FanMountPosition> positions)`
    — 입력 순서를 바깥 루프 `i`, 안쪽 루프 `j > i`로 돌고, 각 쌍에 방향 4가지를 붙인다. ID는 `P01`부터 순번.

- [ ] **Step 1: 실패하는 테스트를 추가한다**

`FanLayoutScoreModelTest.java`에 추가 (import 문에 `java.util.*` 추가):

```java
    @Test
    @DisplayName("6위치 전수 열거는 15쌍 × 4방향 = 60조합이고 ID가 P01~P60이다")
    void enumeratesSixtyCombinations() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));

        assertEquals(60, all.size());
        assertEquals("P01", all.get(0).id());
        assertEquals("P60", all.get(59).id());

        // 위치쌍은 순서 없는 조합이라 같은 배치가 두 번 나오면 안 된다.
        Set<String> shapes = new HashSet<>();
        for (FanLayoutCandidate c : all) {
            String a = c.position1().name() + ":" + c.flow1().name();
            String b = c.position2().name() + ":" + c.flow2().name();
            List<String> pair = new ArrayList<>(List.of(a, b));
            Collections.sort(pair);
            assertTrue(shapes.add(String.join("|", pair)), "중복 조합: " + c.id());
        }
    }

    @Test
    @DisplayName("열거 순서가 고정된다 — P02는 하단 흡기 + 상단 배기, P58은 우측 하단 흡기 + 우측 상단 배기")
    void enumerationOrderIsStable() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));
        Map<String, FanLayoutCandidate> byId = new LinkedHashMap<>();
        for (FanLayoutCandidate c : all) byId.put(c.id(), c);

        FanLayoutCandidate p02 = byId.get("P02");
        assertEquals(FanMountPosition.BOTTOM, p02.position1());
        assertEquals(FanFlowRole.INTAKE, p02.flow1());
        assertEquals(FanMountPosition.TOP, p02.position2());
        assertEquals(FanFlowRole.EXHAUST, p02.flow2());

        FanLayoutCandidate p58 = byId.get("P58");
        assertEquals(FanMountPosition.RIGHT_BOTTOM, p58.position1());
        assertEquals(FanFlowRole.INTAKE, p58.flow1());
        assertEquals(FanMountPosition.RIGHT_TOP, p58.position2());
        assertEquals(FanFlowRole.EXHAUST, p58.flow2());
    }

    @Test
    @DisplayName("위치를 2곳으로 줄이면 1쌍 × 4방향 = 4조합만 나온다")
    void enumerationHonoursPositionSubset() {
        List<FanLayoutCandidate> some = FanLayoutScoreModel.enumerateAll(
                List.of(FanMountPosition.BOTTOM, FanMountPosition.TOP));
        assertEquals(4, some.size());
        assertEquals("P01", some.get(0).id());
        assertEquals("P04", some.get(3).id());
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: 컴파일 실패 — `FanLayoutCandidate` / `FanLayoutScoreModel` 심볼 없음

- [ ] **Step 3: 구현한다**

`src/main/java/com/wastesim/edge/layout/FanLayoutCandidate.java`:

```java
package com.wastesim.edge.layout;

/**
 * 팬 2개를 어디에 어떤 역할로 다는지 — 평가 단위 하나.
 *
 * <p>두 팬은 동일 사양(40×40 mm)이므로 위치쌍은 <b>순서 없는 조합</b>이다.
 * 열거는 그래서 {@code j > i}로만 돌고, 같은 배치를 순서만 바꿔 두 번 세지 않는다.
 *
 * @param id        열거 순번 기반 ID(P01~P60). 엑셀 시트의 조합 ID와 같다
 * @param position1 첫 팬의 장착 위치
 * @param flow1     첫 팬의 역할
 * @param position2 둘째 팬의 장착 위치
 * @param flow2     둘째 팬의 역할
 */
public record FanLayoutCandidate(String id,
                                 FanMountPosition position1, FanFlowRole flow1,
                                 FanMountPosition position2, FanFlowRole flow2) {

    /** 두 팬이 같은 자리를 차지하는가 — 물리적으로 불가능한 입력을 거르는 데 쓴다. */
    public boolean hasSamePosition() { return position1 == position2; }

    /** 두 팬의 역할이 같은가(둘 다 흡기이거나 둘 다 배기). 관통류가 아니라는 뜻이다. */
    public boolean hasSameFlow() { return flow1 == flow2; }
}
```

`src/main/java/com/wastesim/edge/layout/FanLayoutScoreModel.java` (이 태스크에서는 열거만; 점수는 Task 3):

```java
package com.wastesim.edge.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 듀얼 팬 배치의 <b>경험적</b> 냉각 점수 모델.
 *
 * <p>출처는 dual_fan_all_layouts_preliminary.xlsx와 그 생성 스크립트
 * build_fan_layouts.mjs(2026-08-27)다. CFD도 실측도 아니고, 팬 풍량·정압, 함체 치수,
 * 통풍구 개구율, 방열판 사양은 하나도 반영돼 있지 않다. 용도는 <b>실측할 배치 후보를
 * 줄이는 것</b> 하나뿐이다.
 *
 * <h3>기존 열 스택을 참조하지 않는다</h3>
 * {@code FanArraySpec}은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고 못 박고
 * 있는데, 이 클래스는 정확히 그 차이를 만드는 모델이다. 그래서 열 스택
 * ({@code ThermalSimulator}·{@code HeatsinkThermalModel}·{@code ThermalParams}·
 * {@code ThermalRun})을 <b>import 하지 않는다</b> — 의존성이 없으면 임시 계수가 물리
 * 모델 결과에 새어 들어갈 경로 자체가 없다(설계 D-43, FanLayoutIsolationTest가 고정).
 */
public final class FanLayoutScoreModel {

    private FanLayoutScoreModel() {}

    /** 방향 조합 4가지 — 엑셀의 열거 순서를 그대로 따른다(ID가 시트와 어긋나면 안 된다). */
    private static final FanFlowRole[][] FLOW_PAIRS = {
            {FanFlowRole.INTAKE,  FanFlowRole.INTAKE},
            {FanFlowRole.INTAKE,  FanFlowRole.EXHAUST},
            {FanFlowRole.EXHAUST, FanFlowRole.INTAKE},
            {FanFlowRole.EXHAUST, FanFlowRole.EXHAUST}
    };

    /**
     * 주어진 위치들에서 만들 수 있는 모든 배치를 센다.
     *
     * <p>순서가 고정돼 있어야 ID(P01~P60)가 엑셀 시트와 일치한다 — 바깥 루프가 위치 i,
     * 안쪽 루프가 j &gt; i, 그 안에서 방향 4가지다. 이 순서를 바꾸면 골든 회귀 테스트가
     * 깨진다.
     *
     * @param positions 열거에 포함할 위치(2곳 이상). 호출측이 중복·개수를 미리 검증한다
     */
    public static List<FanLayoutCandidate> enumerateAll(List<FanMountPosition> positions) {
        List<FanLayoutCandidate> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                for (FanFlowRole[] flows : FLOW_PAIRS) {
                    String id = String.format("P%02d", out.size() + 1);
                    out.add(new FanLayoutCandidate(
                            id, positions.get(i), flows[0], positions.get(j), flows[1]));
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/edge/layout src/test/java/com/wastesim/edge/layout
git commit -m "feat(edge): 듀얼 팬 배치 60조합 전수 열거 (FR-115)"
```

---

## Task 3: 점수식 — 골든 회귀

**Files:**
- Create: `src/main/java/com/wastesim/edge/layout/FanLayoutScore.java`
- Modify: `src/main/java/com/wastesim/edge/layout/FanLayoutScoreModel.java`
- Modify: `src/test/java/com/wastesim/edge/layout/FanLayoutScoreModelTest.java`

**Interfaces:**
- Consumes: `FanLayoutCandidate` (Task 2)
- Produces:
  - `record FanLayoutScore(double coolingScore, FlowType flowType, double pairFactor, double flowBonus, double advisoryPeakTempC, double advisoryMeanTempC, double advisorySpreadC, StagnationRisk stagnationRisk, String interpretation, FanArraySpec.SourceStatus sourceStatus)`
  - 중첩 `enum FlowType { FORCED_THROUGH_FLOW, POSITIVE_PRESSURE, NEGATIVE_PRESSURE }`
  - 중첩 `enum StagnationRisk { LOW, MEDIUM, HIGH }` (`koLabel()` 포함: 낮음/보통/높음)
  - `static FanLayoutScore FanLayoutScoreModel.score(FanLayoutCandidate)`
  - `static double FanLayoutScoreModel.clampScore(double)` — 경계 테스트용으로 공개

- [ ] **Step 1: 실패하는 골든 테스트를 추가한다**

`FanLayoutScoreModelTest.java`에 추가:

```java
    private FanLayoutScore scoreOf(String id) {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            if (c.id().equals(id)) return FanLayoutScoreModel.score(c);
        }
        throw new AssertionError("조합 없음: " + id);
    }

    @Test
    @DisplayName("골든 회귀 — 7개 조합의 점수·예상온도·편차가 엑셀 값과 일치한다")
    void goldenValuesMatchSpreadsheet() {
        // {id, coolingScore, advisoryPeakTempC, advisorySpreadC}
        double[][] golden = {
                {1,  0.7215, 62.5195, 7.785},
                {2,  1.075,  52.975,  2.25},
                {3,  0.825,  59.725,  4.75},
                {5,  0.6747, 63.7831, 8.253},
                {58, 0.83,   59.59,   4.70},
                {59, 0.58,   66.34,   7.20},
                {60, 0.656,  64.288,  8.44}
        };
        for (double[] g : golden) {
            String id = String.format("P%02d", (int) g[0]);
            FanLayoutScore s = scoreOf(id);
            assertEquals(g[1], s.coolingScore(),      1e-9, id + " 냉각점수");
            assertEquals(g[2], s.advisoryPeakTempC(), 1e-9, id + " 예상 최고온도");
            assertEquals(g[3], s.advisorySpreadC(),   1e-9, id + " 예상 편차");
            // 평균은 최고에서 고정 오프셋만큼 내린 값이다(엑셀 M열).
            assertEquals(g[2] - 5.2, s.advisoryMeanTempC(), 1e-9, id + " 예상 평균온도");
        }
    }

    @Test
    @DisplayName("관통류 보정 — 흡기가 낮으면 +0.15, 높으면 -0.10, 같은 높이면 0")
    void throughFlowBonusFollowsNaturalConvection() {
        assertEquals(0.15,  scoreOf("P02").flowBonus(), 1e-9);  // 하단 흡기 → 상단 배기
        assertEquals(-0.10, scoreOf("P03").flowBonus(), 1e-9);  // 하단 배기 ← 상단 흡기
        assertEquals(0.0,   scoreOf("P06").flowBonus(), 1e-9);  // 하단 흡기 → 좌측 하단 배기(같은 높이)
    }

    @Test
    @DisplayName("입출구 단락 — 흡·배기가 같은 측면(중앙 제외)일 때만 -0.12가 붙는다")
    void shortCircuitPenaltyOnlyOnSameSide() {
        // P58: 우측 하단 흡기 + 우측 상단 배기 → 자연대류 +0.15, 단락 -0.12 → 0.03
        assertEquals(0.03, scoreOf("P58").flowBonus(), 1e-9);
        assertTrue(scoreOf("P58").interpretation().contains("입출구 단락 가능"));
        // P02: 둘 다 중앙(하단·상단)이라 단락 페널티가 없다
        assertEquals(0.15, scoreOf("P02").flowBonus(), 1e-9);
        assertFalse(scoreOf("P02").interpretation().contains("단락"));
    }

    @Test
    @DisplayName("흐름 유형과 정체 위험이 점수·방향에서 결정된다")
    void flowTypeAndRiskAreDerived() {
        assertEquals(FanLayoutScore.FlowType.FORCED_THROUGH_FLOW, scoreOf("P02").flowType());
        assertEquals(FanLayoutScore.StagnationRisk.LOW, scoreOf("P02").stagnationRisk());

        assertEquals(FanLayoutScore.FlowType.POSITIVE_PRESSURE, scoreOf("P01").flowType());
        assertEquals(FanLayoutScore.FlowType.NEGATIVE_PRESSURE, scoreOf("P04").flowType());

        assertEquals(FanLayoutScore.StagnationRisk.MEDIUM, scoreOf("P03").stagnationRisk()); // 0.825
        assertEquals(FanLayoutScore.StagnationRisk.HIGH,   scoreOf("P59").stagnationRisk()); // 0.58
    }

    @Test
    @DisplayName("clamp는 6위치 표준 집합에서 한 번도 걸리지 않는다 — 현재 비활성 가드임을 문서화")
    void clampNeverBindsForStandardPositionSet() {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            double s = FanLayoutScoreModel.score(c).coolingScore();
            assertTrue(s > FanLayoutScoreModel.SCORE_MIN,
                    c.id() + " 점수가 하한에 닿았다 — clamp가 순위를 바꾸고 있다: " + s);
            assertTrue(s < FanLayoutScoreModel.SCORE_MAX,
                    c.id() + " 점수가 상한에 닿았다 — clamp가 순위를 바꾸고 있다: " + s);
        }
    }

    @Test
    @DisplayName("clamp 자체는 범위를 벗어난 값에서 동작한다")
    void clampBoundsRawScore() {
        assertEquals(0.25, FanLayoutScoreModel.clampScore(-3.0), 1e-9);
        assertEquals(1.15, FanLayoutScoreModel.clampScore(9.9), 1e-9);
        assertEquals(0.90, FanLayoutScoreModel.clampScore(0.90), 1e-9);
    }

    @Test
    @DisplayName("모든 조합의 신뢰상태가 검증 전 임시값으로 표시된다")
    void everyScoreIsMarkedPreliminary() {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            assertEquals(com.wastesim.edge.FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE,
                    FanLayoutScoreModel.score(c).sourceStatus(), c.id());
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: 컴파일 실패 — `FanLayoutScore` 심볼 없음, `FanLayoutScoreModel.score` 없음

- [ ] **Step 3: `FanLayoutScore`를 구현한다**

`src/main/java/com/wastesim/edge/layout/FanLayoutScore.java`:

```java
package com.wastesim.edge.layout;

import com.wastesim.edge.FanArraySpec;

/**
 * 배치 하나의 평가 결과.
 *
 * <h3>온도는 1급 지표가 아니다</h3>
 * {@code advisory*} 필드는 "무팬 82 ℃"라는 <b>이 모델 안에서만 의미가 있는 앵커</b>에서
 * 선형으로 환산한 값이다. 기존 {@code simulate_edge_throttling}이나
 * {@code simulate_heatsink_layout}은 열저항·주변온도·부하 프로파일에서 온도를 계산하므로,
 * 같은 조건에서도 두 도구의 숫자가 다르다. 그래서 판단 기준은
 * {@link #coolingScore}·{@link #stagnationRisk}·{@link #advisorySpreadC}이고,
 * 온도는 응답에서 별도 블록으로 격리해 경고와 함께 내보낸다(설계 §4.4).
 *
 * @param coolingScore      상대 냉각 점수. 클수록 좋다
 * @param flowType          기류 유형
 * @param pairFactor        방향 조합 계수(점수 재현용으로 남긴다)
 * @param flowBonus         기류 보정(점수 재현용으로 남긴다)
 * @param advisoryPeakTempC 참고용 예상 최고온도(℃) — 시뮬레이터 결과와 비교 불가
 * @param advisoryMeanTempC 참고용 예상 평균온도(℃)
 * @param advisorySpreadC   참고용 예상 위치편차(℃). 작을수록 온도가 고르다
 * @param stagnationRisk    공기 정체 위험
 * @param interpretation    기류 해석 한 줄(한국어)
 * @param sourceStatus      항상 PRELIMINARY_ESTIMATE
 */
public record FanLayoutScore(double coolingScore,
                             FlowType flowType,
                             double pairFactor,
                             double flowBonus,
                             double advisoryPeakTempC,
                             double advisoryMeanTempC,
                             double advisorySpreadC,
                             StagnationRisk stagnationRisk,
                             String interpretation,
                             FanArraySpec.SourceStatus sourceStatus) {

    /** 두 팬이 만드는 함체 기류의 유형. */
    public enum FlowType {
        /** 한쪽이 넣고 다른 쪽이 뺀다 — 유로가 정해진다. */
        FORCED_THROUGH_FLOW("강제 관통류"),
        /** 둘 다 흡기 — 내부가 양압이 되고 배출은 틈에 맡긴다. */
        POSITIVE_PRESSURE("양압/자연배출"),
        /** 둘 다 배기 — 내부가 음압이 되고 흡기는 틈에 맡긴다. */
        NEGATIVE_PRESSURE("음압/자연흡기");

        private final String koLabel;
        FlowType(String koLabel) { this.koLabel = koLabel; }
        public String koLabel() { return koLabel; }
        public String wire() { return name(); }
    }

    /** 공기가 고여 국소 과열이 생길 위험. 점수에서 결정론적으로 정한다. */
    public enum StagnationRisk {
        LOW("낮음"), MEDIUM("보통"), HIGH("높음");

        private final String koLabel;
        StagnationRisk(String koLabel) { this.koLabel = koLabel; }
        public String koLabel() { return koLabel; }
        public String wire() { return name(); }
    }
}
```

- [ ] **Step 4: `FanLayoutScoreModel`에 점수식을 추가한다**

`FanLayoutScoreModel.java`의 `enumerateAll` 아래에 추가하고, 파일 상단 import에 `com.wastesim.edge.FanArraySpec`를 넣는다:

```java
    // ── 계수 (출처: 엑셀 "가정" 시트 + build_fan_layouts.mjs, 2026-08-27) ────────
    // 전부 임시값이다. 실측이 들어오면 앵커와 환산계수부터 교체한다(설계 §4.3).

    /** 무팬 상태의 기준 최고온도(℃) — 예상온도를 환산하는 앵커. */
    public static final double BARE_PEAK_ANCHOR_C = 82.0;
    /** 냉각점수 1.0당 내려가는 온도(℃). */
    public static final double SCORE_TO_DELTA_C = 27.0;
    /** 최고온도와 평균온도의 고정 간격(℃). */
    public static final double MEAN_OFFSET_C = 5.2;

    public static final double SPREAD_BASE = 3.0;
    public static final double SPREAD_SLOPE = 10.0;
    /** 두 팬 역할이 같으면 유로가 정해지지 않아 편차가 커진다. */
    public static final double SAME_DIRECTION_SPREAD_PENALTY = 2.0;

    public static final double SCORE_MIN = 0.25;
    public static final double SCORE_MAX = 1.15;

    public static final double INTAKE_PAIR_FACTOR = 0.78;
    public static final double EXHAUST_PAIR_FACTOR = 0.82;
    public static final double THROUGH_FLOW_FACTOR = 1.0;

    /** 흡기가 배기보다 낮다 — 자연대류와 같은 방향이라 유리하다. */
    public static final double NATURAL_CONVECTION_BONUS = 0.15;
    /** 흡기가 배기보다 높다 — 자연대류를 거스른다. */
    public static final double AGAINST_CONVECTION_PENALTY = -0.10;
    /** 흡·배기가 같은 측면이라 공기가 보드를 지나지 않고 빠져나갈 수 있다. */
    public static final double SHORT_CIRCUIT_PENALTY = -0.12;

    public static final double RISK_LOW_THRESHOLD = 0.95;
    public static final double RISK_MEDIUM_THRESHOLD = 0.78;

    /**
     * 배치 하나를 평가한다.
     *
     * <p>식은 엑셀 K~O열 수식을 그대로 옮긴 것이다.
     * <pre>
     * score = clamp(0.25, 1.15, (eff1 + eff2)/2 * pairFactor + flowBonus)
     * peak  = 82 - score * 27
     * </pre>
     */
    public static FanLayoutScore score(FanLayoutCandidate c) {
        double pairFactor = pairFactor(c);
        double rawBonus = 0.0;
        String note;

        if (!c.hasSameFlow()) {
            // 관통류 — 흡기와 배기가 정해지므로 유로의 방향을 따질 수 있다.
            FanMountPosition intake  = c.flow1() == FanFlowRole.INTAKE  ? c.position1() : c.position2();
            FanMountPosition exhaust = c.flow1() == FanFlowRole.EXHAUST ? c.position1() : c.position2();

            if (intake.level() < exhaust.level()) {
                rawBonus += NATURAL_CONVECTION_BONUS;
                note = "자연대류와 같은 아래→위 흐름";
            } else if (intake.level() > exhaust.level()) {
                rawBonus += AGAINST_CONVECTION_PENALTY;
                note = "자연대류를 거스르는 위→아래 흐름";
            } else {
                note = "같은 높이의 횡류";
            }
            // 중앙은 함체 반대면이라 단락으로 보지 않는다 — 좌·우끼리 겹칠 때만 문제다.
            if (intake.side() == exhaust.side() && intake.side() != FanMountPosition.Side.CENTER) {
                rawBonus += SHORT_CIRCUIT_PENALTY;
                note += "; 입출구 단락 가능";
            }
        } else {
            // 둘 다 흡기이거나 둘 다 배기 — 유로가 팬이 아니라 함체 틈에 맡겨진다.
            note = c.flow1() == FanFlowRole.INTAKE
                    ? "출구 면적에 따라 내부 양압"
                    : "흡기 틈 위치에 따라 내부 음압";
        }

        double meanEfficiency = (c.position1().efficiency() + c.position2().efficiency()) / 2.0;
        double score = clampScore(meanEfficiency * pairFactor + rawBonus);

        double peak = BARE_PEAK_ANCHOR_C - score * SCORE_TO_DELTA_C;
        double spread = SPREAD_BASE + (1 - score) * SPREAD_SLOPE
                + (c.hasSameFlow() ? SAME_DIRECTION_SPREAD_PENALTY : 0.0);

        return new FanLayoutScore(
                score, flowType(c), pairFactor, rawBonus,
                peak, peak - MEAN_OFFSET_C, spread,
                risk(score), note,
                FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE);
    }

    /** 점수를 물리적으로 말이 되는 범위로 자른다. 표준 6위치에서는 걸리지 않는 가드다. */
    public static double clampScore(double raw) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, raw));
    }

    private static double pairFactor(FanLayoutCandidate c) {
        if (!c.hasSameFlow()) return THROUGH_FLOW_FACTOR;
        return c.flow1() == FanFlowRole.INTAKE ? INTAKE_PAIR_FACTOR : EXHAUST_PAIR_FACTOR;
    }

    private static FanLayoutScore.FlowType flowType(FanLayoutCandidate c) {
        if (!c.hasSameFlow()) return FanLayoutScore.FlowType.FORCED_THROUGH_FLOW;
        return c.flow1() == FanFlowRole.INTAKE
                ? FanLayoutScore.FlowType.POSITIVE_PRESSURE
                : FanLayoutScore.FlowType.NEGATIVE_PRESSURE;
    }

    private static FanLayoutScore.StagnationRisk risk(double score) {
        if (score >= RISK_LOW_THRESHOLD) return FanLayoutScore.StagnationRisk.LOW;
        if (score >= RISK_MEDIUM_THRESHOLD) return FanLayoutScore.StagnationRisk.MEDIUM;
        return FanLayoutScore.StagnationRisk.HIGH;
    }
```

- [ ] **Step 5: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: PASS (12 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/wastesim/edge/layout src/test/java/com/wastesim/edge/layout
git commit -m "feat(edge): 배치 냉각점수 모델과 엑셀 골든 회귀 (FR-116)"
```

---

## Task 4: 순위와 동률 규칙

**Files:**
- Create: `src/main/java/com/wastesim/edge/layout/FanLayoutRanking.java`
- Modify: `src/test/java/com/wastesim/edge/layout/FanLayoutScoreModelTest.java`

**Interfaces:**
- Consumes: `FanLayoutCandidate`, `FanLayoutScore`, `FanLayoutScoreModel` (Tasks 2–3)
- Produces:
  - `record FanLayoutRanking.Entry(int rank, FanLayoutCandidate candidate, FanLayoutScore score)`
  - `static List<Entry> FanLayoutRanking.rank(List<FanLayoutCandidate> candidates)`
  - 상수: `String STATUS_RANKED = "RANKED"`, `String TIE_BREAK`, `double TIE_TOLERANCE = 1e-9`,
    `List<String> WARNINGS`, `List<String> RECOMMENDED_MEASUREMENT_STEPS`,
    `String MODEL_KIND = "EMPIRICAL_SCORE_NOT_PHYSICS"`

- [ ] **Step 1: 실패하는 테스트를 추가한다**

`FanLayoutScoreModelTest.java`에 추가:

```java
    @Test
    @DisplayName("1위는 P02(하단 흡기 + 상단 배기)이고 점수 내림차순으로 정렬된다")
    void bestLayoutIsBottomIntakeTopExhaust() {
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));

        assertEquals(60, ranked.size());
        assertEquals(1, ranked.get(0).rank());
        assertEquals("P02", ranked.get(0).candidate().id());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).score().coolingScore() >= ranked.get(i).score().coolingScore(),
                    "정렬이 깨졌다: " + ranked.get(i - 1).candidate().id());
            assertEquals(i + 1, ranked.get(i).rank());
        }
    }

    @Test
    @DisplayName("좌우 대칭 동률은 조합 ID로 갈린다 — P10이 P18보다 앞선다")
    void leftRightTieIsBrokenById() {
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));

        int p10 = -1, p18 = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).candidate().id().equals("P10")) p10 = i;
            if (ranked.get(i).candidate().id().equals("P18")) p18 = i;
        }
        assertTrue(p10 >= 0 && p18 >= 0);

        // 좌·우 위치효율이 대칭이라 점수와 편차가 완전히 같다. 이 동률은 모델의 한계이지
        // 계산 오차가 아니므로, 임의로 우열을 만들지 않고 ID 순서로 고정한다.
        assertEquals(ranked.get(p10).score().coolingScore(),
                     ranked.get(p18).score().coolingScore(), 1e-12);
        assertEquals(ranked.get(p10).score().advisorySpreadC(),
                     ranked.get(p18).score().advisorySpreadC(), 1e-12);
        assertTrue(p10 < p18, "동률이면 ID가 작은 쪽이 앞선다");
        assertEquals(2, ranked.get(p10).rank());
    }

    @Test
    @DisplayName("점수가 같으면 예상 편차가 작은 쪽이 앞선다")
    void tieOnScoreIsBrokenBySpread() {
        // 같은 점수를 만들되 편차만 다르게 — 방향이 같은 조합은 편차에 +2가 붙는다.
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));
        for (int i = 1; i < ranked.size(); i++) {
            FanLayoutRanking.Entry prev = ranked.get(i - 1), cur = ranked.get(i);
            if (Math.abs(prev.score().coolingScore() - cur.score().coolingScore()) < 1e-9) {
                assertTrue(prev.score().advisorySpreadC() <= cur.score().advisorySpreadC() + 1e-9,
                        "동률에서 편차 순서가 깨졌다: " + prev.candidate().id() + " vs " + cur.candidate().id());
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: 컴파일 실패 — `FanLayoutRanking` 심볼 없음

- [ ] **Step 3: 구현한다**

`src/main/java/com/wastesim/edge/layout/FanLayoutRanking.java`:

```java
package com.wastesim.edge.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 배치 후보들을 냉각점수로 줄 세운다.
 *
 * <h3>동률은 실제로 생긴다 — ID 규칙이 장식이 아니다</h3>
 * 좌·우 위치효율이 대칭이라(좌측 상단 0.82 = 우측 상단 0.82) 좌우를 뒤집은 배치는
 * 점수도 편차도 완전히 같아진다. 60조합 중 P10(하단 흡기 + 좌측 상단 배기)과
 * P18(하단 흡기 + 우측 상단 배기)이 그렇고, 이 둘이 실제로 2·3위를 나눠 갖는다.
 *
 * <p>이 동률은 계산 오차가 아니라 <b>모델이 좌우를 구별할 근거를 갖고 있지 않다</b>는
 * 사실 그대로다. 그래서 임의로 우열을 만들지 않고 조합 ID 오름차순으로 고정한다 —
 * 같은 입력이면 항상 같은 순위가 나와야 사용자가 결과를 재현할 수 있다.
 */
public final class FanLayoutRanking {

    private FanLayoutRanking() {}

    public static final String STATUS_RANKED = "RANKED";

    /** 이 도구가 물리 모델이 아님을 응답 최상단에서 밝히는 표식. */
    public static final String MODEL_KIND = "EMPIRICAL_SCORE_NOT_PHYSICS";

    /**
     * 동률 허용 오차. 0으로 두면 부동소수점 끝자리가 순위를 정한다 — 물리적으로
     * 구분되지 않는 차이로 순위가 뒤집히면 사용자가 재현할 수 없다
     * ({@code FanSweepResult.TIE_TOLERANCE}와 같은 이유).
     */
    public static final double TIE_TOLERANCE = 1e-9;

    public static final String TIE_BREAK =
            "coolingScore 동률이면 advisorySpreadC가 작은 쪽, 그래도 같으면 조합 ID 오름차순";

    /**
     * 항상 함께 나가는 경고. 하나라도 빠지면 임시 추정값이 확정값처럼 읽힌다.
     */
    public static final List<String> WARNINGS = List.of(
            "FAN_SPEC_NOT_VERIFIED",
            "ADVISORY_TEMP_ANCHORED_ESTIMATE",
            "ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR");

    /** 엑셀 "추천 결과" 시트의 권장 실측 순서. */
    public static final List<String> RECOMMENDED_MEASUREMENT_STEPS = List.of(
            "상위 3개 배치 각 3회 반복",
            "무팬 및 팬 1개 기준선과 비교",
            "최고온도·노드별 온도·회복시간·소음·전력 기록");

    /** 순위 한 줄. */
    public record Entry(int rank, FanLayoutCandidate candidate, FanLayoutScore score) {}

    public static List<Entry> rank(List<FanLayoutCandidate> candidates) {
        record Scored(FanLayoutCandidate candidate, FanLayoutScore score) {}

        List<Scored> scored = new ArrayList<>();
        for (FanLayoutCandidate c : candidates) scored.add(new Scored(c, FanLayoutScoreModel.score(c)));

        scored.sort(Comparator
                .comparingDouble((Scored s) -> quantize(s.score().coolingScore())).reversed()
                .thenComparingDouble(s -> quantize(s.score().advisorySpreadC()))
                .thenComparing(s -> s.candidate().id()));

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            out.add(new Entry(i + 1, scored.get(i).candidate(), scored.get(i).score()));
        }
        return out;
    }

    /**
     * 허용 오차 안의 차이를 같은 값으로 뭉갠다 — 그래야 다음 비교 기준(편차 → ID)이
     * 실제로 순위를 가른다. 오차를 무시하고 raw 값을 비교하면 1e-15 차이 때문에
     * 편차 규칙이 영원히 발동하지 않는다.
     */
    private static double quantize(double v) {
        return Math.round(v / TIE_TOLERANCE) * TIE_TOLERANCE;
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutScoreModelTest`
Expected: PASS (15 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/edge/layout src/test/java/com/wastesim/edge/layout
git commit -m "feat(edge): 배치 순위와 좌우 대칭 동률 규칙 (FR-116)"
```

---

## Task 5: MCP 도구 `rank_fan_layouts`

**Files:**
- Create: `src/main/java/com/wastesim/edge/layout/RankFanLayoutsTool.java`
- Create: `src/test/java/com/wastesim/edge/layout/RankFanLayoutsToolTest.java`

**Interfaces:**
- Consumes: `FanLayoutRanking`, `FanLayoutScoreModel`, `FanMountPosition`, `FanFlowRole`
- Produces:
  - `@Component class RankFanLayoutsTool implements McpToolProvider`
  - `toolName() == "rank_fan_layouts"`, `domain() == McpDomain.EDGE`
  - `call(JsonNode)` → `ToolResult.ok(Map<String,Object>)` (§6.3 형태) 또는 `ToolResult.rejected(...)`
  - 최상위 키: `tool`, `status`, `modelKind`, `evaluatedCount`, `ranking`, `tieBreak`, `warnings`, `sourceStatus`, `recommendedMeasurementSteps`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/wastesim/edge/layout/RankFanLayoutsToolTest.java`:

```java
package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpDomain;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 도구 계약과 fail-closed 검증. 이 도구는 공용
 * {@code SimulationConfigValidator}를 타지 않으므로 검증 책임이 전부 여기 있다 —
 * 여기가 뚫리면 LLM이 만든 엉뚱한 위치 문자열이 그대로 순위표가 된다.
 */
class RankFanLayoutsToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private final RankFanLayoutsTool tool = new RankFanLayoutsTool();

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(String args) throws Exception {
        ToolResult r = tool.call(json(args));
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    private ToolResult rejected(String args) throws Exception {
        ToolResult r = tool.call(json(args));
        assertFalse(r.ready(), "거부됐어야 한다");
        assertFalse(r.errors().isEmpty());
        return r;
    }

    @Test
    @DisplayName("도구 규약 — 이름·도메인·스키마")
    void toolContract() throws Exception {
        assertEquals("rank_fan_layouts", tool.toolName());
        assertEquals(McpDomain.EDGE, tool.domain());
        assertFalse(tool.description().isBlank());
        JsonNode schema = json(tool.inputSchemaJson());
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").has("positions"));
        assertTrue(schema.path("properties").has("candidates"));
        assertTrue(schema.path("properties").has("topK"));
    }

    @Test
    @DisplayName("인자 없이 부르면 60조합을 평가하고 상위 10개를 돌려준다")
    @SuppressWarnings("unchecked")
    void defaultRunEvaluatesAllSixty() throws Exception {
        Map<String, Object> out = ok("{}");

        assertEquals("rank_fan_layouts", out.get("tool"));
        assertEquals(FanLayoutRanking.STATUS_RANKED, out.get("status"));
        assertEquals(FanLayoutRanking.MODEL_KIND, out.get("modelKind"));
        assertEquals(60, out.get("evaluatedCount"));

        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(10, ranking.size(), "topK 기본값은 10");

        Map<String, Object> first = ranking.get(0);
        assertEquals(1, first.get("rank"));
        assertEquals("P02", first.get("id"));
        assertEquals(1.075, (Double) first.get("coolingScore"), 1e-9);
        assertEquals("FORCED_THROUGH_FLOW", first.get("flowType"));
        assertEquals("LOW", first.get("stagnationRisk"));

        Map<String, Object> fan1 = (Map<String, Object>) first.get("fan1");
        assertEquals("bottom", fan1.get("position"));
        assertEquals("하단", fan1.get("positionKo"));
        assertEquals("intake", fan1.get("flow"));
        assertEquals("흡기", fan1.get("flowKo"));
    }

    @Test
    @DisplayName("예상 온도는 advisory 블록에 격리되고 비교 불가 표식이 붙는다")
    @SuppressWarnings("unchecked")
    void advisoryTempIsQuarantined() throws Exception {
        Map<String, Object> out = ok("{}");
        Map<String, Object> first = ((List<Map<String, Object>>) out.get("ranking")).get(0);

        // 온도가 1급 필드로 새어 나오면 안 된다 — 그러면 시뮬레이터 온도와 섞인다.
        assertFalse(first.containsKey("peakTempC"));
        assertFalse(first.containsKey("advisoryPeakTempC"));

        Map<String, Object> advisory = (Map<String, Object>) first.get("advisory");
        assertEquals(52.975, (Double) advisory.get("peakTempC"), 1e-9);
        assertEquals(47.775, (Double) advisory.get("meanTempC"), 1e-9);
        assertEquals(2.25, (Double) advisory.get("spreadC"), 1e-9);
        assertEquals(82.0, (Double) advisory.get("anchorBarePeakC"), 1e-9);
        assertEquals(Boolean.FALSE, advisory.get("comparableWithSimulator"));
    }

    @Test
    @DisplayName("경고 3종과 신뢰상태가 항상 붙는다")
    @SuppressWarnings("unchecked")
    void warningsAlwaysPresent() throws Exception {
        Map<String, Object> out = ok("{\"topK\":1}");
        List<String> warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.contains("FAN_SPEC_NOT_VERIFIED"));
        assertTrue(warnings.contains("ADVISORY_TEMP_ANCHORED_ESTIMATE"));
        assertTrue(warnings.contains("ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR"));
        assertEquals("PRELIMINARY_ESTIMATE", out.get("sourceStatus"));
        assertEquals(FanLayoutRanking.RECOMMENDED_MEASUREMENT_STEPS,
                out.get("recommendedMeasurementSteps"));
        assertEquals(FanLayoutRanking.TIE_BREAK, out.get("tieBreak"));
    }

    @Test
    @DisplayName("includeAllCombinations=true면 topK와 무관하게 60개를 전부 돌려준다")
    @SuppressWarnings("unchecked")
    void includeAllOverridesTopK() throws Exception {
        Map<String, Object> out = ok("{\"topK\":3,\"includeAllCombinations\":true}");
        assertEquals(60, ((List<Map<String, Object>>) out.get("ranking")).size());
    }

    @Test
    @DisplayName("positions로 열거 범위를 줄일 수 있다")
    @SuppressWarnings("unchecked")
    void positionsNarrowsEnumeration() throws Exception {
        Map<String, Object> out = ok(
                "{\"positions\":[\"bottom\",\"top\"],\"includeAllCombinations\":true}");
        assertEquals(4, out.get("evaluatedCount"));
        assertEquals(4, ((List<Map<String, Object>>) out.get("ranking")).size());
    }

    @Test
    @DisplayName("candidates로 특정 배치만 직접 평가할 수 있다")
    @SuppressWarnings("unchecked")
    void candidatesEvaluateExplicitLayouts() throws Exception {
        Map<String, Object> out = ok("""
            {"candidates":[
              {"fan1":{"position":"하단","flow":"흡기"},"fan2":{"position":"상단","flow":"배기"}},
              {"fan1":{"position":"bottom","flow":"exhaust"},"fan2":{"position":"top","flow":"intake"}}
            ]}""");
        assertEquals(2, out.get("evaluatedCount"));
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(2, ranking.size());
        assertEquals(1.075, (Double) ranking.get(0).get("coolingScore"), 1e-9);
        assertEquals(0.825, (Double) ranking.get(1).get("coolingScore"), 1e-9);
    }

    @Test
    @DisplayName("candidates와 positions를 함께 주면 거부한다")
    void rejectsCandidatesWithPositions() throws Exception {
        ToolResult r = rejected("""
            {"positions":["bottom","top"],
             "candidates":[{"fan1":{"position":"bottom","flow":"intake"},
                            "fan2":{"position":"top","flow":"exhaust"}}]}""");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("위치가 2곳 미만이면 쌍을 만들 수 없어 거부한다")
    void rejectsTooFewPositions() throws Exception {
        ToolResult r = rejected("{\"positions\":[\"bottom\"]}");
        assertEquals(ErrorCode.OUT_OF_RANGE, r.errors().get(0).code());
    }

    @Test
    @DisplayName("위치가 중복되면 거부한다")
    void rejectsDuplicatePositions() throws Exception {
        ToolResult r = rejected("{\"positions\":[\"bottom\",\"bottom\",\"top\"]}");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("알 수 없는 위치·방향은 추측하지 않고 거부한다")
    void rejectsUnknownEnums() throws Exception {
        assertEquals(ErrorCode.INVALID_ENUM,
                rejected("{\"positions\":[\"뒷면\",\"top\"]}").errors().get(0).code());
        assertEquals(ErrorCode.INVALID_ENUM, rejected("""
            {"candidates":[{"fan1":{"position":"bottom","flow":"순환"},
                            "fan2":{"position":"top","flow":"exhaust"}}]}""")
                .errors().get(0).code());
    }

    @Test
    @DisplayName("한 자리에 팬 2개를 다는 배치는 거부한다")
    void rejectsSamePositionForBothFans() throws Exception {
        ToolResult r = rejected("""
            {"candidates":[{"fan1":{"position":"bottom","flow":"intake"},
                            "fan2":{"position":"bottom","flow":"exhaust"}}]}""");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("topK가 범위 밖이면 거부한다")
    void rejectsTopKOutOfRange() throws Exception {
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"topK\":0}").errors().get(0).code());
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"topK\":61}").errors().get(0).code());
    }

    @Test
    @DisplayName("candidates가 빈 배열이면 거부한다")
    void rejectsEmptyCandidates() throws Exception {
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"candidates\":[]}").errors().get(0).code());
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=RankFanLayoutsToolTest`
Expected: 컴파일 실패 — `RankFanLayoutsTool` 심볼 없음

- [ ] **Step 3: 도구를 구현한다**

`src/main/java/com/wastesim/edge/layout/RankFanLayoutsTool.java`:

```java
package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 {@code rank_fan_layouts} — "40 mm 팬 2개를 어디에 어떤 방향으로 달아야
 * 하나"에 순위로 답한다.
 *
 * <p>기본 동작은 장착 위치 6곳에서 만들 수 있는 <b>60조합 전수 평가</b>다
 * (15개 위치쌍 × 4개 방향조합).
 *
 * <h3>이 도구가 내는 온도를 시뮬레이터 온도와 비교하면 안 된다</h3>
 * 예상 온도는 "무팬 82 ℃"라는 임의 앵커에서 선형 환산한 값이라, 열저항·주변온도·부하
 * 프로파일에서 온도를 계산하는 {@code simulate_edge_throttling}과 숫자가 맞지 않는다.
 * 그래서 온도는 응답의 {@code advisory} 블록에만 담고 경고를 항상 함께 낸다.
 * 사용자가 봐야 할 1급 지표는 {@code coolingScore}와 {@code stagnationRisk}다.
 */
@Component
public class RankFanLayoutsTool implements McpToolProvider {

    /** 응답 기본 상위 개수 — 표로 읽을 수 있는 분량. */
    static final int DEFAULT_TOP_K = 10;
    /** 6위치 전수 조합 수. topK 상한이기도 하다. */
    static final int MAX_COMBINATIONS = 60;

    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "rank_fan_layouts"; }

    @Override
    public String description() {
        return "라즈베리파이 함체에 40 mm 팬 2개를 다는 위치(하단·상단·좌우 상하단)와 방향(흡기·배기) "
             + "조합 60가지를 경험적 냉각점수로 순위 매긴다. 기류 유형·정체 위험·예상 온도편차를 함께 낸다. "
             + "실측 전 후보 선별용이며, 예상 온도는 이 도구 안에서만 유효한 임시 추정값이라 "
             + "발열 시뮬레이션 결과와 비교할 수 없다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "positions": {
                  "type": "array",
                  "description": "전수 열거에 포함할 장착 위치(2곳 이상). 생략하면 6곳 전부. candidates와 동시 사용 불가",
                  "items": {"type": "string",
                    "enum": ["bottom", "top", "left_bottom", "left_top", "right_bottom", "right_top"]}
                },
                "candidates": {
                  "type": "array",
                  "description": "직접 지정한 배치 후보. positions와 동시 사용 불가",
                  "items": {
                    "type": "object",
                    "properties": {
                      "fan1": {"type": "object", "properties": {
                        "position": {"type": "string"},
                        "flow": {"type": "string", "enum": ["intake", "exhaust"]}}},
                      "fan2": {"type": "object", "properties": {
                        "position": {"type": "string"},
                        "flow": {"type": "string", "enum": ["intake", "exhaust"]}}}
                    },
                    "required": ["fan1", "fan2"]
                  }
                },
                "topK": {"type": "integer", "description": "응답에 담을 상위 개수(1~60)", "default": 10},
                "includeAllCombinations": {"type": "boolean",
                  "description": "true면 topK와 무관하게 평가한 모든 조합을 반환", "default": false}
              }
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        JsonNode root = args == null ? null : args;
        boolean hasPositions = has(root, "positions");
        boolean hasCandidates = has(root, "candidates");

        // 열거 범위와 직접 지정을 동시에 주면 무엇을 평가해야 하는지 모순이다.
        // 한쪽을 조용히 무시하면 사용자는 자기가 준 조건이 반영된 줄 안다.
        if (hasPositions && hasCandidates) {
            return reject(ErrorCode.INVALID_ARGUMENTS, "candidates",
                    "positions(열거 범위)와 candidates(직접 지정)는 함께 쓸 수 없다. 하나만 지정할 것");
        }

        List<FanLayoutCandidate> candidates;
        if (hasCandidates) {
            Object parsed = parseCandidates(root.get("candidates"));
            if (parsed instanceof ValidationError e) return ToolResult.rejected(e);
            @SuppressWarnings("unchecked")
            List<FanLayoutCandidate> list = (List<FanLayoutCandidate>) parsed;
            candidates = list;
        } else {
            Object parsed = parsePositions(root == null ? null : root.get("positions"));
            if (parsed instanceof ValidationError e) return ToolResult.rejected(e);
            @SuppressWarnings("unchecked")
            List<FanMountPosition> positions = (List<FanMountPosition>) parsed;
            candidates = FanLayoutScoreModel.enumerateAll(positions);
        }

        int topK = DEFAULT_TOP_K;
        if (has(root, "topK")) {
            JsonNode n = root.get("topK");
            if (!n.isNumber()) {
                return reject(ErrorCode.INVALID_ARGUMENTS, "topK", "topK는 정수여야 한다");
            }
            topK = n.asInt();
            if (topK < 1 || topK > MAX_COMBINATIONS) {
                return reject(ErrorCode.OUT_OF_RANGE, "topK",
                        "topK는 1 이상 " + MAX_COMBINATIONS + " 이하여야 한다 (받은 값: " + topK + ")");
            }
        }
        boolean includeAll = has(root, "includeAllCombinations")
                && root.get("includeAllCombinations").asBoolean(false);

        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(candidates);
        int limit = includeAll ? ranked.size() : Math.min(topK, ranked.size());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) rows.add(row(ranked.get(i)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("status", FanLayoutRanking.STATUS_RANKED);
        out.put("modelKind", FanLayoutRanking.MODEL_KIND);
        out.put("evaluatedCount", candidates.size());
        out.put("ranking", rows);
        out.put("tieBreak", FanLayoutRanking.TIE_BREAK);
        out.put("warnings", FanLayoutRanking.WARNINGS);
        out.put("sourceStatus", "PRELIMINARY_ESTIMATE");
        out.put("recommendedMeasurementSteps", FanLayoutRanking.RECOMMENDED_MEASUREMENT_STEPS);
        return ToolResult.ok(out);
    }

    // ── 응답 조립 ────────────────────────────────────────────────────────

    private Map<String, Object> row(FanLayoutRanking.Entry e) {
        FanLayoutCandidate c = e.candidate();
        FanLayoutScore s = e.score();

        Map<String, Object> advisory = new LinkedHashMap<>();
        advisory.put("peakTempC", round(s.advisoryPeakTempC()));
        advisory.put("meanTempC", round(s.advisoryMeanTempC()));
        advisory.put("spreadC", round(s.advisorySpreadC()));
        advisory.put("anchorBarePeakC", FanLayoutScoreModel.BARE_PEAK_ANCHOR_C);
        // 이 한 줄이 클라이언트가 시뮬레이터 온도와 섞지 않게 막는 표식이다.
        advisory.put("comparableWithSimulator", Boolean.FALSE);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", e.rank());
        m.put("id", c.id());
        m.put("fan1", fan(c.position1(), c.flow1()));
        m.put("fan2", fan(c.position2(), c.flow2()));
        m.put("flowType", s.flowType().wire());
        m.put("flowTypeKo", s.flowType().koLabel());
        m.put("coolingScore", round(s.coolingScore()));
        m.put("pairFactor", round(s.pairFactor()));
        m.put("flowBonus", round(s.flowBonus()));
        m.put("stagnationRisk", s.stagnationRisk().wire());
        m.put("stagnationRiskKo", s.stagnationRisk().koLabel());
        m.put("interpretation", s.interpretation());
        m.put("advisory", advisory);
        return m;
    }

    private Map<String, Object> fan(FanMountPosition p, FanFlowRole f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", p.wire());
        m.put("positionKo", p.koLabel());
        m.put("flow", f.wire());
        m.put("flowKo", f.koLabel());
        return m;
    }

    /**
     * 부동소수점 잡음을 자른다. 골든 테스트가 1e-9로 비교하므로 그보다 훨씬 촘촘한
     * 자리에서만 자른다 — 반올림이 값을 바꾸면 엑셀과 어긋난다.
     */
    private double round(double v) { return Math.round(v * 1e12) / 1e12; }

    // ── 입력 파싱 (fail-closed) ──────────────────────────────────────────

    private Object parsePositions(JsonNode node) {
        if (node == null || node.isNull()) return List.of(FanMountPosition.values());
        if (!node.isArray()) {
            return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "positions", "positions는 배열이어야 한다");
        }
        LinkedHashSet<FanMountPosition> seen = new LinkedHashSet<>();
        for (JsonNode n : node) {
            FanMountPosition p = FanMountPosition.parse(n.asText(null));
            if (p == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, "positions",
                        "알 수 없는 장착 위치: " + n.asText()
                        + " (bottom·top·left_bottom·left_top·right_bottom·right_top 중 하나)");
            }
            if (!seen.add(p)) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "positions",
                        "장착 위치가 중복됐다: " + p.wire());
            }
        }
        if (seen.size() < 2) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "positions",
                    "팬 2개를 배치하려면 위치가 2곳 이상이어야 한다 (받은 개수: " + seen.size() + ")");
        }
        return new ArrayList<>(seen);
    }

    private Object parseCandidates(JsonNode node) {
        if (!node.isArray()) {
            return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "candidates", "candidates는 배열이어야 한다");
        }
        if (node.isEmpty()) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "candidates",
                    "평가할 배치가 하나도 없다");
        }
        if (node.size() > MAX_COMBINATIONS) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "candidates",
                    "배치 후보는 " + MAX_COMBINATIONS + "개 이하여야 한다 (받은 개수: " + node.size() + ")");
        }
        List<FanLayoutCandidate> out = new ArrayList<>();
        int index = 0;
        for (JsonNode n : node) {
            index++;
            String field = "candidates[" + (index - 1) + "]";
            FanMountPosition p1 = FanMountPosition.parse(n.path("fan1").path("position").asText(null));
            FanFlowRole f1 = FanFlowRole.parse(n.path("fan1").path("flow").asText(null));
            FanMountPosition p2 = FanMountPosition.parse(n.path("fan2").path("position").asText(null));
            FanFlowRole f2 = FanFlowRole.parse(n.path("fan2").path("flow").asText(null));

            if (p1 == null || p2 == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, field,
                        "알 수 없는 장착 위치다 (bottom·top·left_bottom·left_top·right_bottom·right_top)");
            }
            if (f1 == null || f2 == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, field,
                        "알 수 없는 팬 역할이다 (intake·exhaust 또는 흡기·배기)");
            }
            if (p1 == p2) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, field,
                        "같은 자리에 팬 2개를 달 수 없다: " + p1.koLabel());
            }
            out.add(new FanLayoutCandidate(String.format("P%02d", index), p1, f1, p2, f2));
        }
        return out;
    }

    private static boolean has(JsonNode root, String field) {
        return root != null && root.has(field) && !root.get(field).isNull();
    }

    private static ToolResult reject(ErrorCode code, String field, String message) {
        return ToolResult.rejected(new ValidationError(code, field, message));
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=RankFanLayoutsToolTest`
Expected: PASS (14 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/edge/layout src/test/java/com/wastesim/edge/layout
git commit -m "feat(edge): rank_fan_layouts MCP 도구와 fail-closed 입력 검증 (FR-115~117)"
```

---

## Task 6: 격리 규칙과 레지스트리 등록

**Files:**
- Create: `src/test/java/com/wastesim/edge/layout/FanLayoutIsolationTest.java`
- Modify: `src/test/java/com/wastesim/edge/EdgeMcpToolsTest.java`

**Interfaces:**
- Consumes: `RankFanLayoutsTool` (Task 5), `McpToolRegistry`
- Produces: 없음 (테스트만)

- [ ] **Step 1: 격리 테스트를 쓴다**

`src/test/java/com/wastesim/edge/layout/FanLayoutIsolationTest.java`:

```java
package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설계 D-43을 소스 스캔으로 고정한다.
 *
 * <p>{@code FanArraySpec}은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고
 * 명시하는데, 이 패키지의 점수 모델은 정확히 그 차이를 만든다. 두 세계를 나누는 유일한
 * 실질적 장치가 <b>컴파일 의존성이 없다는 사실</b>이다 — 참조가 생기는 순간 임시 계수가
 * 물리 모델 결과로 흘러들 통로가 열린다.
 *
 * <p>이런 규칙은 리뷰어의 기억에 맡기면 반드시 새므로 테스트로 고정한다.
 */
class FanLayoutIsolationTest {

    /** 이 패키지가 참조하면 안 되는 열 스택 타입. */
    private static final List<String> FORBIDDEN = List.of(
            "ThermalSimulator", "HeatsinkThermalModel", "ThermalParams", "ThermalRun");

    @Test
    @DisplayName("layout 패키지가 열 시뮬레이션 스택을 참조하지 않는다 (D-43)")
    void layoutPackageDoesNotTouchThermalStack() throws IOException {
        Path dir = Path.of("src", "main", "java", "com", "wastesim", "edge", "layout");
        assertTrue(Files.isDirectory(dir), "패키지 디렉터리를 찾을 수 없다: " + dir.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (String type : FORBIDDEN) {
                    // javadoc의 {@code ...} 언급은 의존성이 아니므로 코드에서만 본다.
                    String stripped = src.replaceAll("(?s)/\\*.*?\\*/", "")
                                         .replaceAll("(?m)//.*$", "");
                    if (stripped.contains(type)) {
                        violations.add(f.getFileName() + " → " + type);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "배치 점수 모델이 열 스택을 참조한다 — 임시 계수가 물리 결과로 샌다: " + violations);
    }

    @Test
    @DisplayName("FanArraySpec은 SourceStatus enum만 쓴다")
    void onlySourceStatusIsBorrowedFromFanArraySpec() throws IOException {
        Path dir = Path.of("src", "main", "java", "com", "wastesim", "edge", "layout");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String stripped = Files.readString(f)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                int at = stripped.indexOf("FanArraySpec");
                while (at >= 0) {
                    String after = stripped.substring(at);
                    assertTrue(after.startsWith("FanArraySpec.SourceStatus")
                                    || after.startsWith("FanArraySpec;"),
                            f.getFileName() + "에서 FanArraySpec을 SourceStatus 외 용도로 쓴다");
                    at = stripped.indexOf("FanArraySpec", at + 1);
                }
            }
        }
    }
}
```

- [ ] **Step 2: 실행해 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=FanLayoutIsolationTest`
Expected: PASS (2 tests) — Task 1~5를 규칙대로 구현했다면 바로 통과한다. 실패하면 위반한 import를 제거한다.

- [ ] **Step 3: 레지스트리 등록 테스트를 갱신한다**

`src/test/java/com/wastesim/edge/EdgeMcpToolsTest.java`의 필드 선언부에 추가:

```java
    private final com.wastesim.edge.layout.RankFanLayoutsTool fanLayout =
            new com.wastesim.edge.layout.RankFanLayoutsTool();
```

`toolsRegisterWithValidSchemas()`의 본문 첫 두 줄을 다음으로 교체하고, `@DisplayName`을 `"다섯 도구가 레지스트리에 등록되고 스키마가 유효한 JSON이다"`로 바꾼다:

```java
        var registry = new McpToolRegistry(List.of(throttling, layout, calibrate, sweep, fanLayout));
        assertEquals(5, registry.all().size());
```

같은 메서드 끝에 다음 단언을 추가한다:

```java
        // 배치 랭킹 도구도 엣지 엔드포인트에 노출돼야 채팅·MCP 양쪽에서 부를 수 있다.
        assertSame(fanLayout, registry.byToolName("rank_fan_layouts"));
        assertEquals(com.wastesim.mcp.McpDomain.EDGE, fanLayout.domain());
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=EdgeMcpToolsTest+FanLayoutIsolationTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/wastesim/edge
git commit -m "test(edge): 배치 모델 격리 규칙(D-43)과 도구 등록 고정"
```

---

## Task 7: 채팅 라우팅

**Files:**
- Modify: `src/main/java/com/wastesim/service/EdgeToolSelector.java`
- Modify: `src/test/java/com/wastesim/edge/EdgeChatRoutingTest.java`

**Interfaces:**
- Consumes: 없음 (문자열 상수만)
- Produces: `EdgeToolSelector.TOOL_LAYOUT == "rank_fan_layouts"`, `select(String)`이 여섯 값 중 하나를 반환

- [ ] **Step 1: 실패하는 라우팅 테스트를 추가한다**

`src/test/java/com/wastesim/edge/EdgeChatRoutingTest.java`에 추가:

```java
    @Test
    @DisplayName("팬 어휘와 배치 어휘가 함께 있으면 배치 랭킹으로 간다")
    void routesFanPlacementQuestionsToLayoutRanking() {
        assertEquals(EdgeToolSelector.TOOL_LAYOUT,
                EdgeToolSelector.select("팬 두 개를 어디에 달아야 제일 시원해?"));
        assertEquals(EdgeToolSelector.TOOL_LAYOUT,
                EdgeToolSelector.select("흡기 배기 조합 중 뭐가 나아?"));
        assertEquals(EdgeToolSelector.TOOL_LAYOUT,
                EdgeToolSelector.select("40mm 팬 2개 위치 조합 전부 비교해줘"));
    }

    @Test
    @DisplayName("'최적 팬 배치'는 스윕이 아니라 배치 랭킹이다")
    void optimalFanPlacementBeatsSweep() {
        // SWEEP 패턴의 '최적...팬'이 이 문장을 먼저 잡으면 회전수 곡선이 돌아와
        // 사용자가 물어본 '배치'가 답에서 통째로 빠진다.
        assertEquals(EdgeToolSelector.TOOL_LAYOUT,
                EdgeToolSelector.select("최적 팬 배치 알려줘"));
    }

    @Test
    @DisplayName("배치 어휘가 없는 팬 질문은 그대로 스윕으로 간다")
    void fanSpeedQuestionsStayOnSweep() {
        assertEquals(EdgeToolSelector.TOOL_SWEEP, EdgeToolSelector.select("최적 팬 rpm은?"));
        assertEquals(EdgeToolSelector.TOOL_SWEEP, EdgeToolSelector.select("팬 몇 %가 가성비 좋아?"));
    }

    @Test
    @DisplayName("팬 어휘가 없는 배치 질문은 그대로 방열판 도구로 간다")
    void heatsinkPlacementIsUnaffected() {
        assertEquals(EdgeToolSelector.TOOL_HEATSINK,
                EdgeToolSelector.select("방열판을 어디에 붙일까?"));
        assertEquals(EdgeToolSelector.TOOL_HEATSINK,
                EdgeToolSelector.select("핀 방향을 어떻게 정렬해야 해?"));
    }

    @Test
    @DisplayName("제어 방식 질문은 배치보다 PTM이 먼저다")
    void ptmStillWinsOverLayout() {
        assertEquals(EdgeToolSelector.TOOL_PTM,
                EdgeToolSelector.select("팬을 미리 돌리면 이득이야?"));
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=EdgeChatRoutingTest`
Expected: 컴파일 실패 — `TOOL_LAYOUT` 심볼 없음

- [ ] **Step 3: 셀렉터를 수정한다**

`EdgeToolSelector.java`의 상수 블록에 추가:

```java
    public static final String TOOL_LAYOUT = "rank_fan_layouts";
```

`PTM` 패턴 선언 바로 아래에 새 패턴을 추가한다:

```java
    /**
     * <b>팬 배치</b> 요청 — 팬을 "어디에 어떤 방향으로" 달지 묻는 질문이다.
     *
     * <p>팬 어휘와 배치 어휘가 <b>둘 다</b> 있을 때만 고른다. 한쪽만으로 고르면 기존
     * 두 도구를 망가뜨린다.
     *
     * <p>배치 어휘만 보면 {@link #HEATSINK}의 영역을 뺏는다 — "방열판을 어디에 붙일까"가
     * 팬 배치 순위표로 새면, 이 파일에 이미 기록된 오라우팅(냉각 조건을 비교 대상으로
     * 착각하는 유형)과 같은 결함이 하나 더 생긴다.
     *
     * <p>팬 어휘만 보면 {@link #SWEEP}의 영역을 뺏는다 — "최적 팬 rpm"이 배치 랭킹으로
     * 새면 회전수 곡선이 사라진다.
     *
     * <p>반대로 <b>스윕보다는 먼저</b> 봐야 한다. SWEEP의 {@code 최적\s*(의\s*)?(…|팬|…)}가
     * "최적 팬 배치"를 먼저 잡아 버리기 때문이다. 그 문장에서 사용자가 물은 것은
     * 회전수가 아니라 배치다.
     */
    private static final Pattern FAN_LAYOUT = Pattern.compile(
            "(?=.*(팬|fan|쿨러|흡기|배기|흡배기))"
            + "(?=.*(배치|조합|어느\\s*(위치|자리)|위치"
            + "|어디\\s*에?\\s*(달|붙|장착|부착)"
            + "|(상단|하단|앞|뒤|좌우)\\s*.{0,4}(달|붙|장착|부착)"
            + "))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
```

`select()`의 검사 순서를 다음으로 바꾼다(주석도 함께 갱신):

```java
    /** @return 호출할 MCP 도구 이름. 엣지 도메인이면 항상 여섯 중 하나를 반환한다(기본은 발열 시뮬레이션). */
    public static String select(String text) {
        if (text == null) return TOOL_THROTTLING;
        // 검사 순서: PTM(제어 방식) → 팬 배치 → 스윕(고정 운전점) → 캘리브레이션 → 방열판 배치 → 발열.
        // 어휘가 겹치는 구간이 많아, 항상 더 구체적인 쪽을 먼저 본다. 팬 배치를 스윕보다
        // 먼저 보는 이유는 FAN_LAYOUT 주석에 있다.
        if (PTM.matcher(text).find()) return TOOL_PTM;
        if (FAN_LAYOUT.matcher(text).find()) return TOOL_LAYOUT;
        if (SWEEP.matcher(text).find()) return TOOL_SWEEP;
        if (CALIBRATE.matcher(text).find()) return TOOL_CALIBRATE;
        if (HEATSINK.matcher(text).find()) return TOOL_HEATSINK;
        if (HEATSINK_MATERIAL.matcher(text).find() && !MASS.matcher(text).find()) return TOOL_HEATSINK;
        return TOOL_THROTTLING;
    }
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=EdgeChatRoutingTest`
Expected: PASS. 기존 라우팅 테스트가 하나라도 깨지면 `FAN_LAYOUT`의 배치 어휘가 너무 넓은 것이다 — 깨진 문장을 확인해 어휘를 좁힌다(패턴을 넓히지 말 것).

- [ ] **Step 5: 전체 테스트로 회귀를 확인한다**

Run: `./mvnw.cmd -q test`
Expected: 기존 테스트 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/wastesim/service/EdgeToolSelector.java src/test/java/com/wastesim/edge/EdgeChatRoutingTest.java
git commit -m "feat(edge): 팬 배치 질문을 rank_fan_layouts로 라우팅 (FR-118)"
```

---

## Task 8: 채팅 응답 배선

**Files:**
- Modify: `src/main/java/com/wastesim/model/ChatMessage.java`
- Modify: `src/main/java/com/wastesim/edge/EdgeChatFormatter.java`
- Modify: `src/main/java/com/wastesim/controller/ChatController.java`
- Modify: `src/test/java/com/wastesim/edge/EdgeChatRoutingTest.java`

**Interfaces:**
- Consumes: `RankFanLayoutsTool` 출력 `Map` (Task 5)
- Produces:
  - `ChatMessage.MessageType.EDGE_LAYOUT`, `getEdgeLayout()` / `setEdgeLayout(Map<String,Object>)`
  - `static String EdgeChatFormatter.fanLayout(Map<String,Object> out)`

- [ ] **Step 1: 실패하는 포매터 테스트를 추가한다**

`EdgeChatRoutingTest.java`에 추가:

```java
    @Test
    @DisplayName("배치 랭킹 요약에 1위 조합과 임시값 경고가 들어간다")
    @SuppressWarnings("unchecked")
    void fanLayoutSummaryNamesWinnerAndFlagsEstimate() throws Exception {
        var tool = new com.wastesim.edge.layout.RankFanLayoutsTool();
        ToolResult r = tool.call(om.readTree("{\"topK\":3}"));
        assertTrue(r.ready());
        String text = EdgeChatFormatter.fanLayout((Map<String, Object>) r.result());

        assertTrue(text.contains("P02"), "1위 조합 ID가 있어야 한다");
        assertTrue(text.contains("하단") && text.contains("상단"), "배치를 사람 말로 설명해야 한다");
        assertTrue(text.contains("흡기") && text.contains("배기"));
        // 임시 추정값을 확정값처럼 읽게 두지 않는다.
        assertTrue(text.contains("임시") || text.contains("잠정"),
                "예상 온도가 임시값임을 밝혀야 한다: " + text);
        assertTrue(text.contains("60"), "평가한 조합 수를 밝혀야 한다");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=EdgeChatRoutingTest`
Expected: 컴파일 실패 — `EdgeChatFormatter.fanLayout` 없음

- [ ] **Step 3: `ChatMessage`에 타입과 필드를 추가한다**

`MessageType` enum을 다음으로 바꾼다:

```java
    public enum MessageType { USER, BOT, SYSTEM, RESULT, CONFIRM, SCENARIO, EDGE_RESULT, EDGE_SWEEP, EDGE_LAYOUT }
```

`edgeSweep` 필드 선언 아래에 추가:

```java
    /**
     * EDGE_LAYOUT 전용 — {@code rank_fan_layouts}가 돌려준 결과 원본.
     *
     * <p>{@link #edgeSweep}과 따로 두는 이유는 같다. 배치 랭킹은 곡선이 아니라
     * 순위표이고, 온도가 1급 지표가 아니라 {@code advisory} 블록에 격리돼 있다.
     * 같은 필드에 담으면 클라이언트가 키 모양을 보고 어느 쪽인지 추측해야 하고,
     * 그러다 임시 온도를 시뮬레이터 온도 자리에 그리게 된다.
     */
    private java.util.Map<String, Object> edgeLayout;
```

`edgeSweep`의 getter/setter 아래에 추가:

```java
    public java.util.Map<String, Object> getEdgeLayout() { return edgeLayout; }
    public void setEdgeLayout(java.util.Map<String, Object> m) { this.edgeLayout = m; }
```

(`edgeSweep`의 getter/setter가 어디 있는지는 `grep -n "edgeSweep" src/main/java/com/wastesim/model/ChatMessage.java`로 찾는다.)

- [ ] **Step 4: 포매터를 추가한다**

`EdgeChatFormatter.java`의 `fanSweep` 메서드 아래에 추가:

```java
    /**
     * 배치 랭킹 요약. 사용자가 표를 보기 전에 <b>무엇이 1위이고 왜인지</b>, 그리고
     * <b>이 숫자를 어디까지 믿어도 되는지</b>를 먼저 읽게 한다.
     */
    @SuppressWarnings("unchecked")
    public static String fanLayout(Map<String, Object> out) {
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        if (ranking == null || ranking.isEmpty()) {
            return "평가할 배치 후보가 없습니다.";
        }
        StringBuilder sb = new StringBuilder();
        Object evaluated = out.get("evaluatedCount");
        sb.append("팬 배치 ").append(evaluated).append("개 조합을 상대 비교했습니다.\n\n");

        Map<String, Object> top = ranking.get(0);
        Map<String, Object> f1 = (Map<String, Object>) top.get("fan1");
        Map<String, Object> f2 = (Map<String, Object>) top.get("fan2");
        sb.append("1위 ").append(top.get("id")).append(" — ")
          .append(f1.get("positionKo")).append(' ').append(f1.get("flowKo"))
          .append(" + ")
          .append(f2.get("positionKo")).append(' ').append(f2.get("flowKo"))
          .append('\n');
        sb.append("  냉각점수 ").append(top.get("coolingScore"))
          .append(" · 기류 ").append(top.get("flowTypeKo"))
          .append(" · 정체 위험 ").append(top.get("stagnationRiskKo")).append('\n');
        sb.append("  ").append(top.get("interpretation")).append("\n\n");

        int shown = Math.min(3, ranking.size());
        for (int i = 1; i < shown; i++) {
            Map<String, Object> r = ranking.get(i);
            Map<String, Object> a = (Map<String, Object>) r.get("fan1");
            Map<String, Object> b = (Map<String, Object>) r.get("fan2");
            sb.append(r.get("rank")).append("위 ").append(r.get("id")).append(" — ")
              .append(a.get("positionKo")).append(' ').append(a.get("flowKo")).append(" + ")
              .append(b.get("positionKo")).append(' ').append(b.get("flowKo"))
              .append(" (점수 ").append(r.get("coolingScore")).append(")\n");
        }

        // 이 문단이 빠지면 임시 추정값이 시뮬레이션 결과처럼 읽힌다.
        sb.append("\n이 결과는 통풍구 접근성과 예상 유로를 반영한 임시 계수에 기댄 상대 비교입니다. ")
          .append("함께 표시되는 예상 온도는 무팬 82℃를 기준점으로 환산한 임시값이라, ")
          .append("발열 시뮬레이션이 계산한 온도와 직접 비교할 수 없습니다.\n");
        sb.append("권장 실측 순서: ");
        List<String> steps = (List<String>) out.get("recommendedMeasurementSteps");
        if (steps != null) sb.append(String.join(" → ", steps));
        return sb.toString();
    }
```

- [ ] **Step 5: `ChatController`에 분기를 넣는다**

`TOOL_PTM` 분기 아래에 추가:

```java
        // 배치 랭킹도 비교 분기를 타지 않는다 — 배치 자체가 이미 비교 축이라,
        // 여기에 보드·재질 비교를 겹치면 "1위 배치"가 여러 개인 표가 된다.
        if (EdgeToolSelector.TOOL_LAYOUT.equals(toolName)) {
            runEdgeFanLayout(provider, args, userText, history);
            return;
        }
```

`runEdgeFanSweep` 메서드 아래에 추가:

```java
    /**
     * 팬 배치 랭킹 — 스윕·PTM과 같은 이유로 도구 한 번으로 끝난다. 조합 반복은 도구
     * 안에 있으므로 채팅으로 물으나 MCP로 부르나 같은 순위가 나온다.
     *
     * <p>{@code args}를 그대로 넘기지 않고 비워서 부른다. 이 도구의 입력은 배치 후보와
     * topK뿐인데, {@code EdgeParamGuard}가 채워 주는 값은 보드·냉각·부하처럼 전부
     * 열 시뮬레이션용이라 여기서는 의미가 없다. 넘기면 스키마에 없는 키가 섞여 들어간다.
     */
    private void runEdgeFanLayout(McpToolProvider provider, ObjectNode args,
                                  String userText, List<Map<String, String>> history) {
        ToolResult tr = provider.call(JsonNodeFactory.instance.objectNode());
        if (!tr.ready()) {
            replyValidationErrors(tr, userText, history);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tr.result();

        String text = EdgeChatFormatter.fanLayout(out);
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.EDGE_LAYOUT, text);
        msg.setEdgeLayout(out);
        msg.setDomain("edge");
        messaging.convertAndSend("/topic/messages", msg);
        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", text));
        while (history.size() > 20) history.remove(0);
    }
```

`ChatController.java` 상단 import에 `com.fasterxml.jackson.databind.node.JsonNodeFactory`가 없으면 추가한다.

- [ ] **Step 6: 테스트 통과를 확인한다**

Run: `./mvnw.cmd -q test -Dtest=EdgeChatRoutingTest`
Expected: PASS

- [ ] **Step 7: 전체 테스트**

Run: `./mvnw.cmd -q test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/wastesim/model/ChatMessage.java src/main/java/com/wastesim/edge/EdgeChatFormatter.java src/main/java/com/wastesim/controller/ChatController.java src/test/java/com/wastesim/edge/EdgeChatRoutingTest.java
git commit -m "feat(edge): EDGE_LAYOUT 채팅 응답 배선과 요약 포매터 (FR-117·118)"
```

---

## Task 9: 브라우저 렌더링

**Files:**
- Modify: `src/main/resources/static/js/edge.js`

**Interfaces:**
- Consumes: `msg.edgeLayout` (Task 8의 응답 원본)
- Produces: 없음 (프런트엔드 종단)

- [ ] **Step 1: 렌더러를 추가한다**

`edge.js`의 `edgeBuildSweepBubble` 함수 아래, `// ── 도메인 등록 ───` 주석 위에 추가:

```javascript
// ── 팬 배치 랭킹 (EDGE_LAYOUT) ─────────────────────────────────────
// 온도를 1급 지표로 그리지 않는다. 이 도구의 예상 온도는 무팬 82℃ 앵커에서 환산한
// 임시값이라, 발열 시뮬레이션 결과와 같은 자리에 같은 모양으로 그리면 사용자가
// 두 숫자를 비교하게 된다. 그래서 막대는 냉각점수로 그리고, 온도 칸에는 뱃지를 붙인다.

const EDGE_LAYOUT_RISK_CLASS = { LOW: 'ok', MEDIUM: 'warn', HIGH: 'bad' };

function edgeLayoutBanner(layout) {
  return (
    `<div class="edge-layout-banner">` +
      `<b>실측 전 임시 예측</b> — 조합 ${layout.evaluatedCount}개를 상대 비교한 후보 선별 결과입니다. ` +
      `예상 온도는 무팬 82℃를 기준점으로 환산한 값이라 발열 시뮬레이션 온도와 비교할 수 없습니다.` +
    `</div>`
  );
}

function edgeLayoutChart(layout) {
  const rows = layout.ranking || [];
  if (!rows.length) return '';
  const max = Math.max(...rows.map(r => r.coolingScore));
  const bars = rows.map(r => {
    const pct = max > 0 ? (r.coolingScore / max) * 100 : 0;
    return (
      `<div class="edge-layout-bar">` +
        `<span class="edge-layout-bar-label">${r.rank}. ${r.id}</span>` +
        `<span class="edge-layout-bar-track">` +
          `<i style="width:${pct.toFixed(1)}%"></i>` +
        `</span>` +
        `<span class="edge-layout-bar-value">${r.coolingScore.toFixed(3)}</span>` +
      `</div>`
    );
  }).join('');
  return `<div class="edge-layout-chart"><div class="edge-layout-chart-title">냉각점수 (클수록 좋음)</div>${bars}</div>`;
}

function edgeLayoutTable(layout) {
  const rows = (layout.ranking || []).map(r => {
    const risk = EDGE_LAYOUT_RISK_CLASS[r.stagnationRisk] || 'warn';
    const adv = r.advisory || {};
    return (
      `<tr>` +
        `<td>${r.rank}</td>` +
        `<td>${r.id}</td>` +
        `<td>${r.fan1.positionKo} ${r.fan1.flowKo}<br>${r.fan2.positionKo} ${r.fan2.flowKo}</td>` +
        `<td>${r.flowTypeKo}</td>` +
        `<td>${r.coolingScore.toFixed(3)}</td>` +
        `<td class="edge-risk-${risk}">${r.stagnationRiskKo}</td>` +
        `<td>${adv.peakTempC != null ? adv.peakTempC.toFixed(1) : '-'}` +
          `<span class="edge-advisory-badge" title="무팬 82℃ 앵커 기준 임시 환산값 — 시뮬레이터 온도와 비교 불가">임시</span></td>` +
        `<td>${adv.spreadC != null ? adv.spreadC.toFixed(1) : '-'}</td>` +
        `<td class="edge-layout-note">${r.interpretation}</td>` +
      `</tr>`
    );
  }).join('');
  return (
    `<div class="edge-table-wrap"><table class="edge-table edge-layout-table">` +
      `<thead><tr>` +
        `<th>순위</th><th>ID</th><th>배치</th><th>기류</th><th>냉각점수</th>` +
        `<th>정체 위험</th><th>예상 최고(℃)</th><th>예상 편차(℃)</th><th>해석</th>` +
      `</tr></thead><tbody>${rows}</tbody></table></div>`
  );
}

function edgeBuildLayoutBubble(msg) {
  const layout = msg.edgeLayout;
  const div = document.createElement('div');
  div.className = 'msg bot edge-result';
  if (!layout || !(layout.ranking || []).length) {   // 원본이 없으면 텍스트만 — 안전한 폴백
    div.textContent = msg.content;
    return div;
  }
  div.innerHTML =
    edgeLayoutBanner(layout) +
    edgeLayoutChart(layout) +
    edgeLayoutTable(layout) +
    `<details class="edge-notes"><summary>해석과 주의사항</summary><pre>${
        (msg.content || '').replace(/[<>&]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]))
    }</pre></details>`;
  return div;
}
```

- [ ] **Step 2: `renderMessage`에 분기를 등록한다**

`Domains.register({...})`의 `renderMessage`에서 `EDGE_SWEEP` 분기 아래에 추가:

```javascript
    if (msg.type === 'EDGE_LAYOUT') {
      document.getElementById('messages').appendChild(edgeBuildLayoutBubble(msg));
      return true;
    }
```

- [ ] **Step 3: 사이드바 칩을 추가한다**

`chips` 배열의 `{ label: '최적 팬 속도', run: () => edgeFanSweep() },` 아래에 추가:

```javascript
    { label: '팬 배치 조합', text: '팬 두 개를 어디에 어떤 방향으로 달아야 제일 시원해?' },
```

- [ ] **Step 4: 스타일을 추가한다**

`src/main/resources/static/css/app.css`(경로는 `grep -rn "edge-sweep-legend" src/main/resources/static/css/`로 확인) 의 `edge-sweep` 관련 규칙 아래에 추가:

```css
/* 배치 랭킹 — 임시 예측임을 시각적으로 계속 상기시킨다 */
.edge-layout-banner {
  background: #fef3c7; color: #92400e; border-radius: 6px;
  padding: 8px 10px; margin-bottom: 10px; font-size: 12px; line-height: 1.5;
}
.edge-layout-chart { margin-bottom: 12px; }
.edge-layout-chart-title { font-size: 12px; color: #6b7280; margin-bottom: 6px; }
.edge-layout-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; font-size: 12px; }
.edge-layout-bar-label { width: 72px; flex: none; color: #374151; }
.edge-layout-bar-track { flex: 1; background: #e5e7eb; border-radius: 3px; height: 12px; }
.edge-layout-bar-track > i { display: block; height: 100%; background: #2563eb; border-radius: 3px; }
.edge-layout-bar-value { width: 48px; flex: none; text-align: right; color: #374151; }
.edge-advisory-badge {
  display: inline-block; margin-left: 4px; padding: 0 4px; border-radius: 3px;
  background: #fef3c7; color: #92400e; font-size: 10px; vertical-align: middle;
}
.edge-layout-note { max-width: 220px; font-size: 11px; color: #6b7280; }
```

- [ ] **Step 5: 앱을 띄워 눈으로 확인한다**

앱을 실행하고 (`./mvnw.cmd spring-boot:run`) `/edge` 화면에서 사이드바 "팬 배치 조합" 칩을 누른다.
확인할 것: 노란 임시 예측 배너, 냉각점수 막대(1위 P02), 표의 예상 온도 칸에 "임시" 뱃지, 정체 위험 색상.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/static/js/edge.js src/main/resources/static/css/app.css
git commit -m "feat(edge): 배치 랭킹 화면 — 냉각점수 막대와 임시값 표기"
```

---

## Task 10: 명세 반영

**Files:**
- Modify: `docs/specifications/docs_waste-sim-spring_SRS_SDD_TDD_v1_12.md`
- Modify: `docs/reference/FAN_RPM_SWEEP_DESIGN.md`

**Interfaces:**
- Consumes: Tasks 1–9의 구현 결과
- Produces: 없음 (문서 종단)

- [ ] **Step 1: FR을 추가한다**

`docs_waste-sim-spring_SRS_SDD_TDD_v1_12.md`의 FR-114 항목 바로 뒤에, 같은 표 형식으로 네 줄을 추가한다.
번호는 **FR-115~118**이다(현재 최대가 FR-114임을 `grep -o "FR-1[0-9][0-9]" | sort -u | tail -1`로 재확인할 것).

| 번호 | 요구사항 |
|---|---|
| FR-115 | 시스템은 장착 위치 6곳에서 만들 수 있는 듀얼 팬 배치를 전수 열거해야 한다 — 15개 위치쌍 × 4개 방향조합 = 60조합. 위치쌍은 순서 없는 조합이며 조합 ID는 P01~P60으로 고정한다 |
| FR-116 | 시스템은 각 배치의 냉각점수를 위치효율·방향계수·기류보정에서 산출하고, 점수 내림차순 → 예상 편차 오름차순 → 조합 ID 오름차순으로 순위를 매겨야 한다 |
| FR-117 | 시스템은 예상 온도를 응답의 별도 `advisory` 블록에 격리하고, `FAN_SPEC_NOT_VERIFIED`·`ADVISORY_TEMP_ANCHORED_ESTIMATE`·`ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR` 경고를 항상 함께 반환해야 한다 |
| FR-118 | 시스템은 팬 어휘와 배치 어휘가 함께 있는 요청만 `rank_fan_layouts`로 라우팅해야 하며, 검사 순서는 PTM → 팬 배치 → 스윕 → 캘리브레이션 → 방열판 → 발열이다 |

- [ ] **Step 2: UT를 추가한다**

UT 표의 마지막(UT-267 뒤)에 추가한다. 각 항목은 Tasks 1–8에서 실제로 작성한 테스트와 1:1로 대응한다.

| 번호 | 항목 | 검증 | 근거 |
|---|---|---|---|
| UT-268 | 위치 계수 | 6위치의 높이·측면·위치효율이 엑셀 가정 시트와 일치 | FR-115 |
| UT-269 | 표기 수용 | 위치·방향이 영문 키와 한글 라벨을 모두 받고 모르는 값은 null | FR-115 |
| UT-270 | 전수 열거 | 60조합, ID P01~P60, 중복 없음 | FR-115 |
| UT-271 | 열거 순서 | P02·P58이 지정된 위치·방향과 일치 | FR-115 |
| UT-272 | 범위 축소 | 위치 2곳이면 4조합 | FR-115 |
| UT-273 | 골든 회귀 | 7개 조합의 점수·예상온도·편차가 엑셀과 1e-9 이내 일치 | FR-116 |
| UT-274 | 관통류 보정 | 자연대류 방향 +0.15, 역방향 -0.10, 횡류 0 | FR-116 |
| UT-275 | 단락 페널티 | 흡·배기가 같은 측면(중앙 제외)일 때만 -0.12 | FR-116 |
| UT-276 | clamp 비활성 | 표준 6위치에서 clamp가 한 번도 걸리지 않음 | FR-116 |
| UT-277 | 순위 | 1위 P02, 점수 내림차순 정렬 | FR-116 |
| UT-278 | 좌우 대칭 동률 | P10·P18이 점수·편차 동일, ID로 갈려 P10이 2위 | FR-116 |
| UT-279 | 온도 격리 | 온도가 1급 필드에 없고 `advisory.comparableWithSimulator=false` | FR-117 |
| UT-280 | 경고 상시 | 경고 3종과 `sourceStatus=PRELIMINARY_ESTIMATE`가 항상 포함 | FR-117 |
| UT-281 | 입력 거부 | positions+candidates 동시, 위치 2곳 미만·중복, 알 수 없는 enum, 같은 자리 두 팬, topK 범위 밖, 빈 candidates | FR-115 |
| UT-282 | 격리 규칙 | layout 패키지가 열 스택 4개 타입을 참조하지 않음 | D-43 |
| UT-283 | 라우팅 | 팬+배치는 랭킹, 배치 없는 팬 질문은 스윕, 팬 없는 배치 질문은 방열판, PTM 우선 | FR-118 |

- [ ] **Step 3: 설계 결정 D-43을 추가한다**

D-42 항목 뒤에 추가한다:

> **D-43 — 배치 점수 모델을 열 스택과 컴파일 의존성 수준에서 격리한다.**
> `FanArraySpec`은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고 명시하는데,
> `rank_fan_layouts`의 점수 모델은 정확히 그 차이를 만드는 임시 계수다. 두 세계를 나누는
> 유일한 실질적 장치가 참조가 없다는 사실이므로, `com.wastesim.edge.layout` 패키지는
> `ThermalSimulator`·`HeatsinkThermalModel`·`ThermalParams`·`ThermalRun`을 import 하지
> 않는다(`FanArraySpec`은 `SourceStatus` enum 값만 빌린다). 리뷰어의 기억에 맡기면 반드시
> 새므로 `FanLayoutIsolationTest`가 소스 스캔으로 고정한다.
> **대안으로 검토한 것:** 위치효율을 `HeatsinkThermalModel`의 R_ja에 곱하는 방식. 임시
> 추정값이 기존 도구의 모든 결과에 조용히 섞이고, 나중에 "이 결과가 실측이었나 추정이었나"를
> 복원할 수 없어 기각했다.

- [ ] **Step 4: 추적성 표와 SDD 절을 갱신한다**

- FR↔UT 추적성 표에 두 줄 추가:
  - `| **FR-115~117 팬 배치 랭킹(v1.12)** | **UT-268~282** |`
  - `| **FR-118 배치 라우팅(v1.12)** | **UT-283** |`
- 요구사항 요약 줄(`| 요구사항 | FR-39, FR-48·49, ... **FR-114(v1.12)** |`)에 `**FR-115~118(v1.12)**`를 덧붙인다.
- SDD 2.15 계열 마지막 절 뒤에 새 절 `2.15.x 팬 배치 조합 랭킹(FR-115~118)`을 만들고, 설계 문서 §3(아키텍처)·§4(점수 모델)·§6(도구 계약)의 요지를 옮긴다. 격리 규칙은 D-43을 참조한다.

- [ ] **Step 5: 스윕 설계 문서에 상호 참조를 넣는다**

`docs/reference/FAN_RPM_SWEEP_DESIGN.md` §12의 마지막 문단 뒤에 추가:

```markdown
> **관련 도구:** `rank_fan_layouts`(FR-115~118)가 팬 위치·방향 조합을 경험적 점수로
> 순위 매기지만, 그 도구도 이 절의 한계를 해소하지 않는다 — 겹침 면적·거리·풍속 분포·
> 조건별 실측 `R_ja` 없이 만든 임시 계수이고, 열 스택과 격리돼 있어 여기 계산에
> 관여하지 않는다(D-43). 면적 효율 주장은 여전히 실측 보정 이후의 일이다.
```

- [ ] **Step 6: 전체 테스트로 최종 확인**

Run: `./mvnw.cmd -q test`
Expected: 전부 PASS. 실행된 테스트 총 개수를 기록해 명세 §16 완료 기준에 반영한다.

- [ ] **Step 7: 커밋**

```bash
git add docs/specifications docs/reference
git commit -m "docs: 팬 배치 랭킹을 명세에 반영 (FR-115~118, UT-268~283, D-43)"
```

---

## Self-Review

**Spec coverage:**

| 설계 §  | 담당 태스크 |
|---|---|
| §3.1 격리 규칙 (D-43) | Task 6 (`FanLayoutIsolationTest`), Task 10 (D-43 문서화) |
| §4.1 위치 6곳 | Task 1 |
| §4.1 60조합 열거 | Task 2 |
| §4.2 계산식 · §4.3 명명 상수 | Task 3 |
| §4.4 온도 격리 | Task 3 (`FanLayoutScore`), Task 5 (`advisory` 블록) |
| §5 데이터 모델 7파일 | Tasks 1–5 |
| §6.1 입력 스키마 · §6.2 검증 7규칙 · §6.3 출력 | Task 5 |
| §6.4 정렬·동률 (P10/P18 포함) | Task 4 |
| §7.1 신규 테스트 전 항목 | Tasks 1–6 |
| §7.2 기존 테스트 보강 | Task 6 (`EdgeMcpToolsTest`), Task 7 (`EdgeChatRoutingTest`) |
| §8 라우팅 (순서·패턴·8케이스) | Task 7 |
| §9 채팅·UI | Tasks 8–9 |
| §10 명세 반영 | Task 10 |

빠진 요구사항 없음.

**Placeholder scan:** "TBD"·"적절히 처리"·"위와 비슷하게" 없음. 모든 코드 스텝에 실제 코드가 들어 있다.

**Type consistency:**
- `FanMountPosition.efficiency()` / `level()` / `side()` / `koLabel()` / `wire()` — Task 1 정의, Tasks 3·5에서 같은 이름으로 사용 ✓
- `FanLayoutCandidate.hasSameFlow()` / `hasSamePosition()` — Task 2 정의, Task 3·5 사용 ✓
- `FanLayoutScore.FlowType` / `StagnationRisk`의 `wire()`·`koLabel()` — Task 3 정의, Task 5 사용 ✓
- `FanLayoutRanking.Entry(rank, candidate, score)` — Task 4 정의, Task 5의 `row()` 사용 ✓
- `FanLayoutScoreModel.SCORE_MIN` / `SCORE_MAX` / `BARE_PEAK_ANCHOR_C` — Task 3 정의, Task 3 테스트·Task 5 `advisory` 사용 ✓
- `EdgeChatFormatter.fanLayout(Map)` — Task 8 정의, 같은 태스크 테스트·`ChatController` 사용 ✓
- `msg.edgeLayout` — Task 8 서버 필드, Task 9 클라이언트 사용 ✓
