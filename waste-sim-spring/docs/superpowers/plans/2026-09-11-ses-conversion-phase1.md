# 장량동 시뮬레이터 SES 변환 1단계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 34개 서브태스크 문항을 손으로 적은 목록이 아니라 SES 트리의 가지치기 지점에서 유도하고, 사용자 답변이 그 트리를 가지치기해 시뮬레이션 설정을 만들게 한다.

**Architecture:** 새 패키지 `com.wastesim.ses`에 SES 트리를 선언하고, 트리를 훑어 가지치기 지점(`DecisionPoint`)을 뽑는다. 지점이 문항 골격을 낳고, 문면은 v4에서 이관한 별도 파일이 붙인다. 답변은 `SesPruner`가 PES로 접고, `PesFlattener`가 `SimulationConfig`로 편다. 기존 `toConfig()`는 참조 구현으로 남겨 두고 차등 테스트로 대조한다.

**Tech Stack:** Java 21 · Spring Boot · Jackson · JUnit 5 (`org.junit.jupiter.api.Assertions.*`) · Maven wrapper

**Spec:** `docs/superpowers/specs/2026-09-11-ses-conversion-design.md`

## Global Constraints

- 기존 동작을 바꾸지 않는다. 같은 답변 → 같은 `SimulationConfig` → 같은 시드에 같은 숫자.
- `reference-ses.json`을 **런타임에 읽지 않는다.** 테스트에서만 파일 경로로 읽는다 (`Path.of("docs/research/s1-ses-extraction/reference-ses.json")`). 클래스패스에 두지 않는다.
- `SimulationEngine`·`SimulationConfig`·python 어댑터를 **수정하지 않는다.** 1단계의 변경은 `com.wastesim.ses`, `com.wastesim.subtask`, 리소스 파일에 한정한다.
- 세트 리소스는 덮어쓰지 않고 새 버전을 추가한다(D-45). v4는 그대로 둔다.
- 테스트 실행: `./mvnw test -Dtest=<클래스명>` · 전체는 `./mvnw test`
- 주석과 문서는 한국어로 쓴다. 기존 파일의 문체를 따른다 — 무엇을 하는지가 아니라 **왜 그렇게 했는지**를 적는다.

## 유도의 강도는 지점 종류마다 다르다 — 미리 알고 시작할 것

대응표 34행을 종류별로 세면 이렇게 닫힌다.

```
 4  spec 축 선택      scenarioType · dischargeTimeMode · truckType · travelTimeMode
 3  multi 복제 수     numBuildings · residentsPerBuilding · truckCount
 1  커플링 활성 조건  trafficMode
21  속성 값
──
29  SES에 자리가 있는 것
 1  SES 밖 (engine — 실행 수단)
 4  절차 제어 (simulationGoal · defaultApproval · inputAndScenarioConfirmed · executionApproval)
──
34
```

**SES가 각 종류에 대해 알려 주는 양이 다르다. 이것이 이 작업의 발견이고, 숨기지 않고 측정한다.**

| 지점 종류 | 문항의 존재 | `answerType` | `allowedRange` |
|---|---|---|---|
| `SpecChoice` | SES에서 | SES에서 (`ENUM`) | **SES에서** — 축의 자식 이름이 곧 허용값 |
| `MultiCount` | SES에서 | SES에서 (`INTEGER`) | 선언 |
| `CouplingActivation` | SES에서 | SES에서 (`ENUM`) | 선언 |
| `AttributeValue` | SES에서 | 선언 | 선언 |

SES의 속성은 이름만 있고 자료형이 없다. 그래서 속성 21개는 "물어봐야 한다"까지만 SES가 정하고 "어떻게 묻는가"는 대응 선언이 정한다. `SpecChoice` 4개만이 허용값까지 전부 구조에서 나온다.

---

### Task 1: 골든 테스트 — 엔진 결과를 지금 상태로 못 박는다

1단계는 엔진을 건드리지 않으므로 이 테스트는 지금 당장은 통과한다. 2단계에서 엔진을 쪼갤 때 되돌아갈 지점을 지금 만들어 둔다.

**Files:**
- Create: `src/test/java/com/wastesim/simulation/SimulationEngineGoldenTest.java`
- Create: `src/test/resources/golden/` (스냅샷 3개는 Step 3이 만든다)

**Interfaces:**
- Consumes: `SimulationEngine.run(SimulationConfig, int seed)` · `TrafficDataService` 기본 생성자
- Produces: 없음 (테스트 전용)

- [ ] **Step 1: 스냅샷을 찍는 테스트를 쓴다 — 처음에는 파일이 없어 실패한다**

```java
package com.wastesim.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔진 결과를 못 박는다. 2단계에서 {@code SimulationEngine.run()}을 엔티티별 컴포넌트로
 * 쪼갤 때, 숫자가 흔들렸는지를 이 스냅샷이 판정한다.
 *
 * <p>스냅샷을 <b>파일</b>로 두는 이유: 기댓값을 테스트 코드에 적으면 값을 고칠 때 코드를
 * 고치게 되고, 그러면 "고쳐도 되는 변화"와 "고치면 안 되는 변화"가 같은 diff에 섞인다.
 * 파일이 바뀐 diff는 리뷰에서 바로 보인다.
 */
class SimulationEngineGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    /** 값을 새로 뜨려면 이 값을 true로 바꿔 한 번 돌리고 <b>반드시 되돌린다</b>. */
    private static final boolean REGENERATE = false;

    private static Map<String, Consumer<SimulationConfig>> cases() {
        Map<String, Consumer<SimulationConfig>> cases = new LinkedHashMap<>();
        cases.put("baseline", cfg -> { });
        cases.put("tight-capacity", cfg -> {
            cfg.setCapacity(40.0);
            cfg.setThreshold(0.6);
            cfg.setDays(14);
        });
        cases.put("dense", cfg -> {
            cfg.setNumBuildings(8);
            cfg.setResidentsPerBuilding(30);
            cfg.setDays(10);
        });
        return cases;
    }

    @Test
    void engineResultsMatchGoldenSnapshots() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        SimulationEngine engine = new SimulationEngine(new TrafficDataService());

        for (Map.Entry<String, Consumer<SimulationConfig>> e : cases().entrySet()) {
            SimulationConfig cfg = new SimulationConfig();
            e.getValue().accept(cfg);
            SimulationResult result = engine.run(cfg, 1);
            String actual = mapper.writeValueAsString(result);
            Path snapshot = GOLDEN_DIR.resolve(e.getKey() + ".json");

            if (REGENERATE) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
                continue;
            }

            assertTrue(Files.exists(snapshot), "스냅샷이 없다: " + snapshot
                    + " — REGENERATE를 true로 두고 한 번 돌려 만든다");
            assertEquals(Files.readString(snapshot, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    actual.replace("\r\n", "\n"),
                    e.getKey() + " 결과가 스냅샷과 다르다");
        }
    }
}
```

- [ ] **Step 2: 스냅샷이 없어 실패하는 것을 확인한다**

Run: `./mvnw test -Dtest=SimulationEngineGoldenTest`
Expected: FAIL — "스냅샷이 없다: src/test/resources/golden/baseline.json"

- [ ] **Step 3: REGENERATE를 켜고 한 번 돌려 스냅샷을 만든다**

`REGENERATE`를 `true`로 바꾼 뒤:

Run: `./mvnw test -Dtest=SimulationEngineGoldenTest`
Expected: PASS (아무것도 비교하지 않고 파일만 쓴다)

그다음 `REGENERATE`를 `false`로 **되돌린다**.

- [ ] **Step 4: 이제 진짜로 비교하는지 확인한다**

Run: `./mvnw test -Dtest=SimulationEngineGoldenTest`
Expected: PASS — 이번에는 세 파일과 실제로 비교해서 통과한 것이다

`git status`로 `src/test/resources/golden/` 아래 파일 3개가 생겼는지 확인한다. 파일이 비어 있거나 `{}`이면 `SimulationResult`에 getter가 없다는 뜻이므로, 그 경우에만 `mapper.writeValueAsString` 대신 필요한 값을 직접 뽑아 `LinkedHashMap`에 담는 방식으로 바꾼다.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/wastesim/simulation/SimulationEngineGoldenTest.java src/test/resources/golden
git commit -m "test(engine): 엔진 결과를 스냅샷으로 못 박는다"
```

---

### Task 2: SES 핵심 타입

**Files:**
- Create: `src/main/java/com/wastesim/ses/Decomposition.java`
- Create: `src/main/java/com/wastesim/ses/SesEntity.java`
- Create: `src/main/java/com/wastesim/ses/Coupling.java`
- Create: `src/main/java/com/wastesim/ses/EntityStructure.java`
- Test: `src/test/java/com/wastesim/ses/EntityStructureTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `Decomposition(Decomposition.Kind kind, String name, List<String> children)`, `Kind` = `ASPECT|SPEC|MULTI`
  - `SesEntity(String name, List<String> attributes, List<Decomposition> decompositions)`
    - `List<Decomposition> specAxes()` · `Optional<Decomposition> multiAspect()`
  - `Coupling(String from, String to, String mechanism, String activeWhen)`
  - `EntityStructure(String root, Map<String,SesEntity> entities, List<Coupling> couplings)`
    - `SesEntity entity(String name)` — 없으면 `IllegalArgumentException`
    - `Collection<SesEntity> entities()` — 선언 순서 유지
    - `List<String> pathTo(String entityName)` — 루트에서 그 엔티티까지의 이름 경로

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityStructureTest {

    /** 축소판 트리 — 핵심 타입의 동작만 본다. 장량동 실물은 Task 3에서 다룬다. */
    private static EntityStructure tiny() {
        SesEntity root = new SesEntity("루트", List.of(),
                List.of(new Decomposition(Decomposition.Kind.ASPECT, "구성", List.of("차량 집합"))));
        SesEntity fleet = new SesEntity("차량 집합", List.of("개수"),
                List.of(new Decomposition(Decomposition.Kind.MULTI, "차량 다중", List.of("차량"))));
        SesEntity truck = new SesEntity("차량", List.of("적재용량"),
                List.of(new Decomposition(Decomposition.Kind.SPEC, "차종 축", List.of("5톤", "1톤"))));
        SesEntity big = new SesEntity("5톤", List.of(), List.of());
        SesEntity small = new SesEntity("1톤", List.of(), List.of());
        return new EntityStructure("루트",
                Map.of("루트", root, "차량 집합", fleet, "차량", truck, "5톤", big, "1톤", small),
                List.of());
    }

    @Test
    void specAxisIsFoundOnEntity() {
        List<Decomposition> axes = tiny().entity("차량").specAxes();
        assertEquals(1, axes.size());
        assertEquals("차종 축", axes.get(0).name());
        assertEquals(List.of("5톤", "1톤"), axes.get(0).children());
    }

    @Test
    void multiAspectIsFoundOnHolder() {
        assertTrue(tiny().entity("차량 집합").multiAspect().isPresent());
        assertTrue(tiny().entity("차량").multiAspect().isEmpty());
    }

    @Test
    void pathRunsFromRootToEntity() {
        assertEquals(List.of("루트", "차량 집합", "차량"), tiny().pathTo("차량"));
    }

    @Test
    void unknownEntityFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> tiny().entity("없는 것"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=EntityStructureTest`
Expected: FAIL — 컴파일 오류 (`Decomposition` 등이 없다)

- [ ] **Step 3: 타입을 만든다**

```java
// Decomposition.java
package com.wastesim.ses;

import java.util.List;

/**
 * 엔티티 하나를 아래로 여는 방식. SES가 세 가지만 두는 것이 이 구조의 요점이다 —
 * "…로 구성됨"(aspect), "…중 하나"(spec), "동종 개체 다수"(multi).
 *
 * <p>이 셋이 그대로 <b>사용자에게 물어봐야 할 것의 종류</b>가 된다. spec은 골라야 하고,
 * multi는 몇 개인지 정해야 한다. aspect는 묻지 않는다 — 다 함께 있는 것이므로 고를 것이 없다.
 */
public record Decomposition(Kind kind, String name, List<String> children) {

    public enum Kind { ASPECT, SPEC, MULTI }

    public Decomposition {
        children = List.copyOf(children);
    }
}
```

```java
// SesEntity.java
package com.wastesim.ses;

import java.util.List;
import java.util.Optional;

/** SES 트리의 마디 하나 — 이름과 속성, 그리고 아래로 여는 방식들. */
public record SesEntity(String name, List<String> attributes, List<Decomposition> decompositions) {

    public SesEntity {
        attributes = List.copyOf(attributes);
        decompositions = List.copyOf(decompositions);
    }

    /**
     * 이 엔티티가 가진 spec 축들. 하나일 거라고 가정하지 않는다 — 거주민은 직업 축과
     * 배출시각 모델 축 둘을 갖는다(ref-v7 CP-5).
     */
    public List<Decomposition> specAxes() {
        return decompositions.stream()
                .filter(d -> d.kind() == Decomposition.Kind.SPEC)
                .toList();
    }

    public Optional<Decomposition> multiAspect() {
        return decompositions.stream()
                .filter(d -> d.kind() == Decomposition.Kind.MULTI)
                .findFirst();
    }
}
```

```java
// Coupling.java
package com.wastesim.ses;

/**
 * 엔티티 사이로 무엇이 흐르는가.
 *
 * @param activeWhen 이 결합이 살아 있는 조건. {@code null}이면 항상 살아 있다. 교통 결합
 *                   둘은 {@code trafficMode=APPLY}일 때만 산다 — 답변 하나가 값이 아니라
 *                   <b>연결 구조</b>를 바꾸는 자리다
 */
public record Coupling(String from, String to, String mechanism, String activeWhen) { }
```

```java
// EntityStructure.java
package com.wastesim.ses;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SES 트리 한 벌. 이름으로 마디를 찾고, 루트에서 그 마디까지의 경로를 준다. */
public final class EntityStructure {

    private final String root;
    private final Map<String, SesEntity> entities;
    private final List<Coupling> couplings;

    public EntityStructure(String root, Map<String, SesEntity> entities, List<Coupling> couplings) {
        this.root = root;
        this.entities = new LinkedHashMap<>(entities);
        this.couplings = List.copyOf(couplings);
    }

    public String root() {
        return root;
    }

    public SesEntity entity(String name) {
        SesEntity e = entities.get(name);
        if (e == null) throw new IllegalArgumentException("SES에 없는 엔티티: " + name);
        return e;
    }

    /** 선언 순서를 유지한다 — 문항 순서가 매번 같아야 한다. */
    public Collection<SesEntity> entities() {
        return entities.values();
    }

    public List<Coupling> couplings() {
        return couplings;
    }

    /**
     * 루트에서 그 엔티티까지의 이름 경로. 문항이 어느 화면 단계에 속하는지를 이 경로의
     * 두 번째 마디로 정한다.
     */
    public List<String> pathTo(String entityName) {
        List<String> path = new ArrayList<>();
        if (search(root, entityName, path)) return List.copyOf(path);
        throw new IllegalArgumentException("루트에서 닿지 않는 엔티티: " + entityName);
    }

    private boolean search(String current, String target, List<String> path) {
        path.add(current);
        if (current.equals(target)) return true;
        SesEntity e = entities.get(current);
        if (e != null) {
            for (Decomposition d : e.decompositions()) {
                for (String child : d.children()) {
                    if (search(child, target, path)) return true;
                }
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=EntityStructureTest`
Expected: PASS (4건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/ses src/test/java/com/wastesim/ses
git commit -m "feat(ses): SES 트리의 핵심 타입을 세운다"
```

---

### Task 3: 장량동 트리 선언과 드리프트 테스트

51개 엔티티를 손으로 옮겨 적지 않는다 — `reference-ses.json`에서 **한 번 생성**하고, 그 뒤로는 사람이 손으로 고친다. 생성 스크립트를 남기는 이유는 이 최초 변환이 어떻게 이뤄졌는지가 기록으로 남아야 하기 때문이다.

**Files:**
- Create: `docs/research/s1-ses-extraction/gen_structure_java.py`
- Create: `src/main/java/com/wastesim/ses/JangnyangEntityStructure.java` (스크립트가 생성)
- Test: `src/test/java/com/wastesim/ses/JangnyangStructureMatchesReferenceTest.java`

**Interfaces:**
- Consumes: Task 2의 `EntityStructure` · `SesEntity` · `Decomposition` · `Coupling`
- Produces: `JangnyangEntityStructure.get()` → `EntityStructure` (정적 싱글턴)

- [ ] **Step 1: 대조 테스트를 먼저 쓴다**

```java
package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코드에 선언한 트리와 연구 정답지 {@code reference-ses.json}이 갈라졌는지 본다.
 *
 * <p><b>정답지를 런타임에 읽지 않는 이유</b>: 그 파일은 LLM 추출 실험의 채점 기준이다.
 * 제품 코드가 그것을 읽으면 채점 기준을 고치는 일이 시뮬레이터 동작을 바꾸게 된다. 둘을
 * 떼어 놓되 서로를 붙잡게 하는 자리가 이 테스트다 — 갈라지면 깨지고, 어느 쪽이 옳은지는
 * 사람이 정한다.
 */
class JangnyangStructureMatchesReferenceTest {

    private static final Path REFERENCE =
            Path.of("docs/research/s1-ses-extraction/reference-ses.json");

    @Test
    void declaredStructureMatchesReferenceSes() throws Exception {
        assertTrue(Files.exists(REFERENCE), "정답지를 찾을 수 없다: " + REFERENCE.toAbsolutePath());
        JsonNode ref = new ObjectMapper().readTree(Files.readString(REFERENCE));
        EntityStructure declared = JangnyangEntityStructure.get();

        assertEquals(ref.get("root").asText(), declared.root(), "루트 이름이 다르다");

        JsonNode refEntities = ref.get("entities");
        List<String> refNames = new ArrayList<>();
        for (Iterator<String> it = refEntities.fieldNames(); it.hasNext(); ) refNames.add(it.next());
        List<String> declaredNames = declared.entities().stream().map(SesEntity::name).toList();
        assertEquals(refNames, declaredNames, "엔티티 목록 또는 순서가 다르다");

        for (String name : refNames) {
            JsonNode re = refEntities.get(name);
            SesEntity de = declared.entity(name);
            assertEquals(textList(re.get("attrs")), de.attributes(), name + "의 속성이 다르다");

            List<String> refDecs = new ArrayList<>();
            if (re.has("decompositions")) {
                for (JsonNode d : re.get("decompositions")) {
                    refDecs.add(d.get("kind").asText().toUpperCase() + "|" + d.get("name").asText()
                            + "|" + String.join(",", textList(d.get("children"))));
                }
            }
            List<String> decDecs = de.decompositions().stream()
                    .map(d -> d.kind().name() + "|" + d.name() + "|" + String.join(",", d.children()))
                    .toList();
            assertEquals(refDecs, decDecs, name + "의 분해가 다르다");
        }

        assertEquals(ref.get("couplings").size(), declared.couplings().size(), "결합 개수가 다르다");
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null) for (JsonNode n : node) out.add(n.asText());
        return out;
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=JangnyangStructureMatchesReferenceTest`
Expected: FAIL — 컴파일 오류 (`JangnyangEntityStructure`가 없다)

- [ ] **Step 3: 생성 스크립트를 쓴다**

```python
# docs/research/s1-ses-extraction/gen_structure_java.py
"""reference-ses.json을 JangnyangEntityStructure.java로 한 번 옮긴다.

51개를 손으로 옮겨 적으면 오타가 섞이고, 그 오타는 "정답지와 코드가 갈라졌다"는
신호와 구별되지 않는다. 최초 변환만 기계가 하고, 이후 수정은 사람이 자바 쪽에서 한다.

    python gen_structure_java.py > ../../../src/main/java/com/wastesim/ses/JangnyangEntityStructure.java
"""
import json
import pathlib

KIND = {"aspect": "ASPECT", "spec": "SPEC", "multi": "MULTI"}

ref = json.loads((pathlib.Path(__file__).parent / "reference-ses.json").read_text(encoding="utf-8"))


def q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def java_list(items):
    return "List.of(" + ", ".join(items) + ")" if items else "List.of()"


lines = [
    "package com.wastesim.ses;",
    "",
    "import java.util.LinkedHashMap;",
    "import java.util.List;",
    "import java.util.Map;",
    "",
    "/**",
    " * 장량동 시뮬레이터의 SES 트리. 참조 " + ref["_meta"]["version"] + "에서 옮겨 적었고,",
    " * JangnyangStructureMatchesReferenceTest가 그 정답지와 갈라졌는지 지킨다.",
    " *",
    " * <p>이 선언이 <b>문항 세트의 출처</b>다. 여기 spec 축이 하나 늘면 물어볼 것이 하나 는다.",
    " */",
    "public final class JangnyangEntityStructure {",
    "",
    "    private static final EntityStructure INSTANCE = build();",
    "",
    "    private JangnyangEntityStructure() { }",
    "",
    "    public static EntityStructure get() {",
    "        return INSTANCE;",
    "    }",
    "",
    "    private static EntityStructure build() {",
    "        Map<String, SesEntity> e = new LinkedHashMap<>();",
]

for name, ent in ref["entities"].items():
    attrs = java_list([q(a) for a in ent.get("attrs", [])])
    decs = []
    for d in ent.get("decompositions", []):
        decs.append("new Decomposition(Decomposition.Kind.%s, %s, %s)"
                    % (KIND[d["kind"]], q(d["name"]), java_list([q(c) for c in d["children"]])))
    lines.append("        e.put(%s, new SesEntity(%s, %s, %s));"
                 % (q(name), q(name), attrs, java_list(decs)))

lines += ["", "        List<Coupling> couplings = List.of("]
cps = []
for c in ref["couplings"]:
    active = q(c["active_when"]) if c.get("active_when") else "null"
    cps.append("                new Coupling(%s, %s, %s, %s)"
               % (q(c["from"]), q(c["to"]), q(c["mechanism"]), active))
lines.append(",\n".join(cps))
lines += [
    "        );",
    "",
    "        return new EntityStructure(%s, e, couplings);" % q(ref["root"]),
    "    }",
    "}",
]

print("\n".join(lines))
```

- [ ] **Step 4: 스크립트를 돌려 선언을 만든다**

```bash
python docs/research/s1-ses-extraction/gen_structure_java.py > src/main/java/com/wastesim/ses/JangnyangEntityStructure.java
```

생성된 파일을 **읽어 본다.** 엔티티 51개가 있는지, 한글이 깨지지 않았는지, `couplings`가 6개인지 눈으로 확인한다. 파일이 UTF-8이 아니면 `python -X utf8`로 다시 돌린다.

- [ ] **Step 5: 대조 테스트 통과를 확인한다**

Run: `./mvnw test -Dtest=JangnyangStructureMatchesReferenceTest`
Expected: PASS

깨지면 대개 두 가지다 — 파일 인코딩이 UTF-8이 아니거나, 테스트의 작업 디렉터리가 `waste-sim-spring/`이 아니거나. 후자면 경로 단언 메시지에 절대 경로가 찍히므로 바로 보인다.

- [ ] **Step 6: 커밋**

```bash
git add docs/research/s1-ses-extraction/gen_structure_java.py src/main/java/com/wastesim/ses/JangnyangEntityStructure.java src/test/java/com/wastesim/ses/JangnyangStructureMatchesReferenceTest.java
git commit -m "feat(ses): 장량동 SES 트리를 코드에 선언한다"
```

---

### Task 4: 가지치기 지점 추출

**Files:**
- Create: `src/main/java/com/wastesim/ses/DecisionPoint.java`
- Create: `src/main/java/com/wastesim/ses/DecisionPointExtractor.java`
- Test: `src/test/java/com/wastesim/ses/DecisionPointExtractorTest.java`

**Interfaces:**
- Consumes: Task 2·3의 `EntityStructure` · `JangnyangEntityStructure.get()`
- Produces:
  - `sealed interface DecisionPoint { String id(); String entity(); }` 구현 4개:
    - `DecisionPoint.SpecChoice(String entity, String axis, List<String> options)` — `id()` = `"spec:" + entity + ":" + axis`
    - `DecisionPoint.MultiCount(String entity, String member)` — `id()` = `"multi:" + entity`
    - `DecisionPoint.AttributeValue(String entity, String attribute)` — `id()` = `"attr:" + entity + ":" + attribute`
    - `DecisionPoint.CouplingActivation(String couplingFrom, String couplingTo, String condition)` — `id()` = `"coupling:" + couplingFrom + "->" + couplingTo`, `entity()` = `couplingFrom`
  - `DecisionPointExtractor.extract(EntityStructure)` → `List<DecisionPoint>` (선언 순서, 중복 없음)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DecisionPointExtractorTest {

    private static List<DecisionPoint> points() {
        return DecisionPointExtractor.extract(JangnyangEntityStructure.get());
    }

    @Test
    void specAxesBecomeChoicesWithOptionsFromChildren() {
        DecisionPoint.SpecChoice truckType = points().stream()
                .filter(DecisionPoint.SpecChoice.class::isInstance)
                .map(DecisionPoint.SpecChoice.class::cast)
                .filter(p -> p.entity().equals("수거차량"))
                .findFirst().orElseThrow();
        assertEquals("차종 축", truckType.axis());
        assertEquals(List.of("5톤 차량", "2.5톤 차량", "1톤 차량"), truckType.options());
    }

    @Test
    void residentHasTwoSpecAxes() {
        long axes = points().stream()
                .filter(DecisionPoint.SpecChoice.class::isInstance)
                .filter(p -> p.entity().equals("거주민"))
                .count();
        assertEquals(2, axes, "거주민은 직업 축과 배출시각 모델 축 둘을 갖는다(CP-5)");
    }

    @Test
    void multiHolderYieldsCountAndNotDuplicateAttribute() {
        List<DecisionPoint> ps = points();
        assertTrue(ps.stream().anyMatch(p -> p.id().equals("multi:수거차량 집합")));
        assertFalse(ps.stream().anyMatch(p -> p.id().equals("attr:수거차량 집합:개수")),
                "multi 보유자의 개수 속성은 MultiCount와 같은 결정이므로 두 번 세지 않는다");
    }

    @Test
    void otherAttributesOfMultiHolderSurvive() {
        assertTrue(points().stream().anyMatch(p -> p.id().equals("attr:거주민 집합:직업구성")),
                "개수 말고 다른 속성은 그대로 남는다");
    }

    @Test
    void conditionalCouplingsBecomeActivationPoints() {
        List<DecisionPoint> activations = points().stream()
                .filter(DecisionPoint.CouplingActivation.class::isInstance)
                .toList();
        assertEquals(2, activations.size(), "교통 결합 둘만 조건부다");
    }

    @Test
    void aspectsProduceNoDecision() {
        assertFalse(points().stream().anyMatch(p -> p.id().startsWith("aspect:")),
                "aspect는 다 함께 있는 것이므로 고를 것이 없다");
    }

    @Test
    void idsAreUnique() {
        List<DecisionPoint> ps = points();
        Set<String> ids = ps.stream().map(DecisionPoint::id).collect(Collectors.toSet());
        assertEquals(ps.size(), ids.size(), "지점 id가 겹치면 문항이 서로를 덮어쓴다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=DecisionPointExtractorTest`
Expected: FAIL — 컴파일 오류 (`DecisionPoint`가 없다)

- [ ] **Step 3: 타입과 추출기를 만든다**

```java
// DecisionPoint.java
package com.wastesim.ses;

import java.util.List;

/**
 * SES 트리에서 <b>사용자가 정해 주어야 트리가 닫히는 자리</b>.
 *
 * <p>가설이 걸린 자리다 — "SES로 변환하면 무엇을 물어야 하는지가 정해진다"는 주장은,
 * 이 목록이 서브태스크 문항 목록과 맞아떨어지는가로 검증된다.
 *
 * <p>종류마다 SES가 알려 주는 양이 다르다. {@link SpecChoice}는 허용값까지 구조에서
 * 나오지만, {@link AttributeValue}는 "물어야 한다"까지만 나온다 — 속성에는 자료형이 없다.
 */
public sealed interface DecisionPoint {

    String id();

    String entity();

    /** …중 하나. 축의 자식들이 그대로 허용값이 된다. */
    record SpecChoice(String entity, String axis, List<String> options) implements DecisionPoint {
        public SpecChoice {
            options = List.copyOf(options);
        }

        @Override
        public String id() {
            return "spec:" + entity + ":" + axis;
        }
    }

    /** 동종 개체를 몇 개 둘 것인가. */
    record MultiCount(String entity, String member) implements DecisionPoint {
        @Override
        public String id() {
            return "multi:" + entity;
        }
    }

    /** 속성 하나의 값. */
    record AttributeValue(String entity, String attribute) implements DecisionPoint {
        @Override
        public String id() {
            return "attr:" + entity + ":" + attribute;
        }
    }

    /**
     * 이 결합을 살릴 것인가. 값이 아니라 <b>연결 구조</b>를 정하는 답이다 — 교통을 끄면
     * 교통 구역에서 나가는 두 선이 통째로 죽는다.
     */
    record CouplingActivation(String couplingFrom, String couplingTo, String condition)
            implements DecisionPoint {
        @Override
        public String id() {
            return "coupling:" + couplingFrom + "->" + couplingTo;
        }

        @Override
        public String entity() {
            return couplingFrom;
        }
    }
}
```

```java
// DecisionPointExtractor.java
package com.wastesim.ses;

import java.util.ArrayList;
import java.util.List;

/**
 * 트리를 훑어 결정해야 할 자리를 뽑는다.
 *
 * <p><b>aspect에서는 아무것도 나오지 않는다.</b> "…로 구성됨"은 다 함께 있다는 뜻이므로
 * 고를 것이 없다. 물어야 하는 것은 "…중 하나"(spec)와 "몇 개"(multi)와 값이다.
 */
public final class DecisionPointExtractor {

    /** multi 보유자가 복제 수를 적는 속성 이름. 이 속성은 MultiCount와 같은 결정이다. */
    private static final String COUNT_ATTRIBUTE = "개수";

    private DecisionPointExtractor() { }

    public static List<DecisionPoint> extract(EntityStructure structure) {
        List<DecisionPoint> points = new ArrayList<>();

        for (SesEntity entity : structure.entities()) {
            boolean isMultiHolder = entity.multiAspect().isPresent();

            entity.multiAspect().ifPresent(multi -> points.add(
                    new DecisionPoint.MultiCount(entity.name(), multi.children().get(0))));

            for (Decomposition axis : entity.specAxes()) {
                points.add(new DecisionPoint.SpecChoice(entity.name(), axis.name(), axis.children()));
            }

            for (String attribute : entity.attributes()) {
                // 복제 수를 두 번 묻지 않는다 — MultiCount가 이미 그 결정이다.
                if (isMultiHolder && COUNT_ATTRIBUTE.equals(attribute)) continue;
                points.add(new DecisionPoint.AttributeValue(entity.name(), attribute));
            }
        }

        for (Coupling c : structure.couplings()) {
            if (c.activeWhen() != null && !c.activeWhen().isBlank()) {
                points.add(new DecisionPoint.CouplingActivation(c.from(), c.to(), c.activeWhen()));
            }
        }

        return List.copyOf(points);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=DecisionPointExtractorTest`
Expected: PASS (7건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/ses/DecisionPoint.java src/main/java/com/wastesim/ses/DecisionPointExtractor.java src/test/java/com/wastesim/ses/DecisionPointExtractorTest.java
git commit -m "feat(ses): 트리에서 결정해야 할 자리를 뽑는다"
```

---

### Task 5: 지점 ↔ 문항 필드 대응과 34개 닫힘 검사

SES 이름은 한글이고 답변 필드는 영문이다. 사이를 잇는 대응은 유도할 수 없다 — `구성결정-SES-대응표.md`가 코드 대조로 확정한 사람의 지식이다. **이 파일이 이번 작업에서 손으로 적는 거의 유일한 곳이고, 바로 그래서 여기서 수가 맞는지 검사한다.**

**Files:**
- Create: `src/main/java/com/wastesim/ses/SesFieldMapping.java`
- Test: `src/test/java/com/wastesim/ses/SesFieldMappingTest.java`

**Interfaces:**
- Consumes: Task 4의 `DecisionPoint` · `DecisionPointExtractor`
- Produces:
  - `record SesFieldMapping.FieldBinding(String pointId, String answerField, AnswerType answerType, AllowedRange range)`
  - `record SesFieldMapping.NonSesField(String answerField, NonSesField.Reason reason, String why)`, `Reason` = `OUTSIDE_SES|PROCEDURE_CONTROL`
  - `SesFieldMapping.bindings()` → `List<FieldBinding>` — 항목 29개. `collectionTime`과 `collectionTimes`는 **서로 다른 필드이면서 같은 지점**(`attr:수거차량:수거시각`)을 가리킨다. 대응표 16·17번이 "같은 결정의 목록형"이라고 적은 것이 이것이다. 그래서 지점 수는 28, 필드 수는 29다
  - `SesFieldMapping.nonSesFields()` → `List<NonSesField>` (5개)
  - `SesFieldMapping.bindingsFor(String pointId)` → `List<FieldBinding>`

- [ ] **Step 1: 닫힘 검사 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.wastesim.subtask.AnswerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 34문항이 29 + 1 + 4로 닫히는가. 닫히지 않으면 SES에 자리가 없는 것을 묻고 있거나,
 * 자리가 있는데 묻지 않고 있다는 뜻이다 — 둘 다 보고 대상이다.
 */
class SesFieldMappingTest {

    @Test
    void everyBindingPointsAtARealDecisionPoint() {
        Set<String> pointIds = DecisionPointExtractor.extract(JangnyangEntityStructure.get())
                .stream().map(DecisionPoint::id).collect(Collectors.toSet());
        List<String> dangling = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::pointId)
                .filter(id -> !pointIds.contains(id))
                .sorted().toList();
        assertEquals(List.of(), dangling, "SES에 없는 지점을 가리키는 대응이 있다: " + dangling);
    }

    @Test
    void thirtyFourFieldsCloseAsTwentyNinePlusOnePlusFour() {
        long seated = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField).distinct().count();
        long outsideSes = SesFieldMapping.nonSesFields().stream()
                .filter(f -> f.reason() == SesFieldMapping.NonSesField.Reason.OUTSIDE_SES).count();
        long procedure = SesFieldMapping.nonSesFields().stream()
                .filter(f -> f.reason() == SesFieldMapping.NonSesField.Reason.PROCEDURE_CONTROL).count();

        assertEquals(29, seated, "SES에 자리를 가진 필드");
        assertEquals(1, outsideSes, "SES 밖 결정 — engine");
        assertEquals(4, procedure, "절차 제어");
        assertEquals(34, seated + outsideSes + procedure);
    }

    @Test
    void specChoiceOptionsAreNotDeclaredHere() {
        SesFieldMapping.FieldBinding truckType = SesFieldMapping.bindings().stream()
                .filter(b -> b.answerField().equals("truckType")).findFirst().orElseThrow();
        assertEquals(AnswerType.ENUM, truckType.answerType());
        assertNull(truckType.range().values(),
                "spec 축의 허용값은 선언하지 않는다 — 트리의 자식 이름이 곧 허용값이다");
    }

    @Test
    void noFieldIsDeclaredTwice() {
        List<String> fields = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField).toList();
        Set<String> nonSes = new TreeSet<>(SesFieldMapping.nonSesFields().stream()
                .map(SesFieldMapping.NonSesField::answerField).toList());
        assertEquals(fields.size(), Set.copyOf(fields).size(), "같은 필드를 두 번 묶었다");
        assertTrue(fields.stream().noneMatch(nonSes::contains), "SES 안팎에 동시에 적힌 필드가 있다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=SesFieldMappingTest`
Expected: FAIL — 컴파일 오류 (`SesFieldMapping`이 없다)

- [ ] **Step 3: 대응을 선언한다**

`구성결정-SES-대응표.md`의 34행을 그대로 옮긴다. `answerType`·`allowedRange`는 `jangnyang-simulator-v4.json`의 같은 필드에서 가져온다 — **범위 값을 새로 지어내지 않는다.** 아래 코드의 `num(...)` 인자는 v4 파일을 열어 실제 값으로 맞춘다.

```java
package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;

import java.util.List;

/**
 * SES 지점과 답변 필드를 잇는다.
 *
 * <p><b>이것만은 유도할 수 없다.</b> SES의 이름은 한글이고 필드는 영문이며, 둘 사이에
 * 기계가 건널 다리가 없다. {@code 구성결정-SES-대응표.md}가 코드 소비 지점을 짚어 확정한
 * 사람의 지식이고, 이 클래스는 그것을 코드로 옮긴 것이다.
 *
 * <p>그래서 이 자리가 가설의 경계다 — SES는 "무엇을 물어야 하는가"를 정하고, 이 대응은
 * "그것을 어떤 이름으로 묻는가"를 정한다. 수가 맞는지는 {@code SesFieldMappingTest}가 지킨다.
 *
 * <p>{@code spec} 지점의 {@code range.values}는 <b>비워 둔다</b> — 허용값은 트리의 자식
 * 이름이고, 여기 또 적으면 트리를 고쳤을 때 조용히 갈라진다.
 */
public final class SesFieldMapping {

    public record FieldBinding(String pointId, String answerField,
                               AnswerType answerType, AllowedRange range) { }

    public record NonSesField(String answerField, Reason reason, String why) {
        public enum Reason { OUTSIDE_SES, PROCEDURE_CONTROL }
    }

    private SesFieldMapping() { }

    private static AllowedRange desc(String description) {
        return new AllowedRange(description, null, null, null, null, null, null, null, null);
    }

    private static AllowedRange num(String description, double min, double max) {
        return new AllowedRange(description, min, max, null, null, null, null, null, null);
    }

    private static final List<FieldBinding> BINDINGS = List.of(
            // ── spec 축 4개 — 허용값은 트리에서 온다 ────────────────────────────
            new FieldBinding("spec:실험:실험 유형 축", "scenarioType", AnswerType.ENUM,
                    desc("서버가 실제로 구현한 실행 유형 중 하나")),
            new FieldBinding("spec:거주민:배출시각 모델 축", "dischargeTimeMode", AnswerType.ENUM,
                    desc("배출 시각을 무엇으로 정하는가")),
            new FieldBinding("spec:수거차량:차종 축", "truckType", AnswerType.ENUM,
                    desc("수거에 쓰는 차종")),
            new FieldBinding("spec:수거 경로:이동시간 방식 축", "travelTimeMode", AnswerType.ENUM,
                    desc("지점 사이 이동시간을 무엇으로 계산하는가")),

            // ── multi 복제 수 3개 ──────────────────────────────────────────────
            new FieldBinding("multi:수거지점 집합", "numBuildings", AnswerType.INTEGER,
                    num("1 이상 26 이하 — 자동 생성 지점 ID가 Node_A~Node_Z다", 1, 26)),
            new FieldBinding("multi:거주민 집합", "residentsPerBuilding", AnswerType.INTEGER,
                    num("건물당 1명 이상", 1, 500)),
            new FieldBinding("multi:수거차량 집합", "truckCount", AnswerType.INTEGER,
                    num("1대 이상", 1, 20)),

            // ── 커플링 활성 1개 ────────────────────────────────────────────────
            new FieldBinding("coupling:교통 구역.혼잡계수->수거 경로.이동시간", "trafficMode",
                    AnswerType.ENUM, desc("교통 혼잡을 반영할 것인가")),

            // ── 속성 21개 ──────────────────────────────────────────────────────
            new FieldBinding("attr:거주민 집합:직업구성", "occupationPreset", AnswerType.ENUM,
                    desc("직업 구성 프리셋")),
            new FieldBinding("attr:실험:기간", "days", AnswerType.INTEGER, num("1일 이상", 1, 365)),
            new FieldBinding("attr:실험:반복횟수", "seeds", AnswerType.INTEGER, num("1회 이상", 1, 100)),
            new FieldBinding("attr:대상 시스템:1인배출량", "wasteMeanKg", AnswerType.NUMBER,
                    num("1인 1일 배출량(kg)", 0, 100)),
            new FieldBinding("attr:대상 시스템:배출량변동", "wasteSigma", AnswerType.NUMBER,
                    num("배출량의 표준편차", 0, 100)),
            new FieldBinding("attr:거주민:외출시각변동", "leaveSigma", AnswerType.NUMBER,
                    num("외출 시각의 표준편차(분)", 0, 720)),
            new FieldBinding("attr:포항시 배출시간대 기반:배출허용창", "dischargeWindow",
                    AnswerType.TIME_RANGE, desc("HH:MM~HH:MM 한 구간. 자정을 넘을 수 있다")),
            new FieldBinding("attr:폐기물 유형:용량", "capacity", AnswerType.NUMBER,
                    num("수거함 용량(kg)", 0, 100000)),
            new FieldBinding("attr:폐기물 유형:임계값", "threshold", AnswerType.NUMBER,
                    num("민원이 발생하는 적재 비율(0~1)", 0, 1)),
            new FieldBinding("attr:수거차량:수거시각", "collectionTime", AnswerType.TIME,
                    desc("HH:MM 24시간 표기")),
            new FieldBinding("attr:수거차량:수거시각", "collectionTimes", AnswerType.TIME_LIST,
                    desc("하루 여러 번 수거할 때의 시각 목록")),
            new FieldBinding("attr:수거차량:수거요일", "collectionSchedule", AnswerType.ENUM,
                    desc("수거 주기 또는 요일 집합")),
            new FieldBinding("attr:수거차량:적재용량", "routeAvailableCapacityKg", AnswerType.NUMBER,
                    num("한 운행에 실을 수 있는 양(kg)", 0, 100000)),
            new FieldBinding("attr:수거차량:초기적재량", "initialTruckLoadKg", AnswerType.NUMBER,
                    num("출발 시 이미 실려 있는 양(kg)", 0, 100000)),
            new FieldBinding("attr:수거차량:배차간격", "dispatchIntervalMinutes", AnswerType.INTEGER,
                    num("차량 사이 출발 간격(분)", 0, 1440)),
            new FieldBinding("attr:수거차량:지점당수거시간", "serviceMinutesPerSite", AnswerType.INTEGER,
                    num("한 지점에 머무는 시간(분)", 0, 240)),
            new FieldBinding("attr:교통 구역:시간대프로파일", "trafficProfileId", AnswerType.STRING,
                    desc("교통 프로파일 식별자")),
            new FieldBinding("attr:구간 상수:구간이동시간", "routeTravelMinutes", AnswerType.INTEGER,
                    num("지점 사이 고정 이동시간(분)", 0, 240)),
            new FieldBinding("attr:교통구역 근사:구역내이동시간", "intraZoneTravelMinutes",
                    AnswerType.INTEGER, num("같은 구역 안 이동시간(분)", 0, 240)),
            new FieldBinding("attr:교통구역 근사:구역배정가정", "zoneAssignmentRule", AnswerType.ENUM,
                    desc("건물을 교통 구역에 배정하는 가정")),
            new FieldBinding("attr:수거 경로:방문순서", "routeSequence", AnswerType.STRING_LIST,
                    desc("지점 ID를 방문 순서대로 적은 목록"))
    );

    private static final List<NonSesField> NON_SES = List.of(
            new NonSesField("engine", NonSesField.Reason.OUTSIDE_SES,
                    "java냐 python이냐는 대상 시스템의 성질이 아니라 그것을 돌리는 수단이다"),
            new NonSesField("simulationGoal", NonSesField.Reason.PROCEDURE_CONTROL,
                    "실험의 목적 문장. 계산에 쓰이지 않는다(빌더 :381)"),
            new NonSesField("defaultApproval", NonSesField.Reason.PROCEDURE_CONTROL,
                    "서버가 채운 기본값에 동의했는가 — 구성 절차의 제어"),
            new NonSesField("inputAndScenarioConfirmed", NonSesField.Reason.PROCEDURE_CONTROL,
                    "미리보기를 확인했는가 — 구성 절차의 제어"),
            new NonSesField("executionApproval", NonSesField.Reason.PROCEDURE_CONTROL,
                    "실행을 눌렀는가 — 구성 절차의 제어")
    );

    public static List<FieldBinding> bindings() {
        return BINDINGS;
    }

    public static List<NonSesField> nonSesFields() {
        return NON_SES;
    }

    public static List<FieldBinding> bindingsFor(String pointId) {
        return BINDINGS.stream().filter(b -> b.pointId().equals(pointId)).toList();
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=SesFieldMappingTest`
Expected: PASS (4건)

**깨지면 그것이 첫 번째 발견이다.** `everyBindingPointsAtARealDecisionPoint`가 걸리면 지점 id 표기가 트리와 다른 것이므로 id를 맞춘다(트리의 실제 엔티티·축 이름은 `JangnyangEntityStructure.java`에서 확인한다). `thirtyFourFieldsCloseAs...`가 걸리면 숫자를 억지로 맞추지 말고 **무엇이 남거나 모자라는지 적어 두고 다음 태스크로 간다** — Task 7의 보고서가 그것을 다룬다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/ses/SesFieldMapping.java src/test/java/com/wastesim/ses/SesFieldMappingTest.java
git commit -m "feat(ses): SES 지점과 답변 필드의 대응을 선언한다"
```

---

### Task 6: 문항 골격 유도와 문면 이관

**Files:**
- Create: `src/main/java/com/wastesim/ses/SubtaskSkeleton.java`
- Create: `src/main/java/com/wastesim/ses/ProseEntry.java`
- Create: `src/main/java/com/wastesim/ses/ProseCatalog.java`
- Create: `src/main/java/com/wastesim/ses/SesSubtaskDerivation.java`
- Create: `src/main/resources/subtask/jangnyang-prose-v5.json` (Step 3이 만든다)
- Create: `docs/research/s1-ses-extraction/gen_prose_v5.py`
- Test: `src/test/java/com/wastesim/ses/SesSubtaskDerivationTest.java`

**Interfaces:**
- Consumes: Task 4·5의 `DecisionPointExtractor` · `SesFieldMapping`
- Produces:
  - `record SubtaskSkeleton(String pointId, String answerField, AnswerType answerType, AllowedRange allowedRange, boolean required, int group)`
  - `SesSubtaskDerivation.deriveSkeletons()` → `List<SubtaskSkeleton>`
  - `SesSubtaskDerivation.derive(Map<String,ProseEntry> prose)` → `JangnyangSubtaskDefinition` (세트 id `jangnyang-simulator-v5`, 버전 5)
  - `record ProseEntry(String question, String retryQuestion, String validationRule, String completionCondition, boolean allowsNotApplicable, FieldBasis basis)`
  - `ProseCatalog.load()` → `Map<String, ProseEntry>` (키는 `answerField`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.wastesim.subtask.AnswerType;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesSubtaskDerivationTest {

    @Test
    void specChoiceOptionsAreFilledFromTheTree() {
        SubtaskSkeleton truckType = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.answerField().equals("truckType")).findFirst().orElseThrow();
        assertEquals(AnswerType.ENUM, truckType.answerType());
        assertEquals(List.of("5톤 차량", "2.5톤 차량", "1톤 차량"),
                truckType.allowedRange().valuesOrEmpty(),
                "허용값은 선언이 아니라 트리의 자식 이름에서 채워져야 한다");
    }

    @Test
    void specAndMultiAreRequired() {
        List<SubtaskSkeleton> skeletons = SesSubtaskDerivation.deriveSkeletons();
        assertTrue(skeletons.stream().filter(s -> s.pointId().startsWith("spec:"))
                .allMatch(SubtaskSkeleton::required), "spec 축이 안 정해지면 트리가 닫히지 않는다");
        assertTrue(skeletons.stream().filter(s -> s.pointId().startsWith("multi:"))
                .allMatch(SubtaskSkeleton::required), "복제 수가 안 정해지면 모델을 만들 수 없다");
    }

    @Test
    void derivedSetIsFullySpecifiedAndComplete() {
        JangnyangSubtaskDefinition def = SesSubtaskDerivation.derive(ProseCatalog.load());
        assertEquals("jangnyang-simulator-v5", def.subtaskSetId());
        assertEquals(5, def.version());
        assertEquals(34, def.subtasks().size(), "SES 29 + 밖 1 + 절차 4");
        for (JangnyangSubtask st : def.subtasks()) {
            assertTrue(st.isFullySpecified(), st.answerField() + " 문항에 빈 항목이 있다");
        }
    }

    @Test
    void ordersAreUniqueAndContiguous() {
        List<Integer> orders = SesSubtaskDerivation.derive(ProseCatalog.load()).subtasks().stream()
                .map(JangnyangSubtask::order).sorted().toList();
        for (int i = 0; i < orders.size(); i++) {
            assertEquals(i + 1, orders.get(i), "order가 1부터 연속이어야 한다");
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=SesSubtaskDerivationTest`
Expected: FAIL — 컴파일 오류

- [ ] **Step 3: 문면 이관 스크립트를 쓰고 돌린다**

문면을 새로 쓰지 않는다. v4가 이미 34개의 다듬어진 문장을 갖고 있고, 그것을 새로 쓰면 v4와의 대조(Task 7)에서 **문면 차이와 구조 차이가 섞인다.**

```python
# docs/research/s1-ses-extraction/gen_prose_v5.py
"""v4의 문면을 answerField를 키로 하는 v5 문면 파일로 옮긴다.

문면을 새로 쓰지 않는 이유: v5와 v4를 대조할 때 문장이 달라져 있으면 구조가
달라진 것인지 말이 달라진 것인지 구별할 수 없다.

    python docs/research/s1-ses-extraction/gen_prose_v5.py > src/main/resources/subtask/jangnyang-prose-v5.json
"""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[3]
v4 = json.loads((root / "src/main/resources/subtask/jangnyang-simulator-v4.json")
                .read_text(encoding="utf-8"))

prose = {}
for st in v4["subtasks"]:
    prose[st["answerField"]] = {
        "question": st["question"],
        "retryQuestion": st["retryQuestion"],
        "validationRule": st["validationRule"],
        "completionCondition": st["completionCondition"],
        "allowsNotApplicable": st["allowsNotApplicable"],
        "basis": st.get("basis"),
    }

print(json.dumps({"sourceSet": v4["subtaskSetId"], "entries": prose},
                 ensure_ascii=False, indent=2))
```

```bash
python docs/research/s1-ses-extraction/gen_prose_v5.py > src/main/resources/subtask/jangnyang-prose-v5.json
```

파일을 열어 `entries`가 34개인지 확인한다.

- [ ] **Step 4: 유도기를 만든다**

```java
// SubtaskSkeleton.java
package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;

/**
 * SES가 정해 주는 문항의 뼈대 — 무엇을 어떤 형식으로 묻는가까지.
 * 말투와 근거는 들어 있지 않다. 그것은 구조가 모르는 것이다.
 */
public record SubtaskSkeleton(String pointId, String answerField, AnswerType answerType,
                              AllowedRange allowedRange, boolean required, int group) { }
```

```java
// ProseEntry.java
package com.wastesim.ses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wastesim.subtask.FieldBasis;

/** 사람이 쓴 부분. 구조가 모르는 것들만 여기 있다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProseEntry(String question, String retryQuestion, String validationRule,
                         String completionCondition, boolean allowsNotApplicable,
                         FieldBasis basis) { }
```

```java
// ProseCatalog.java
package com.wastesim.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;

/** {@code answerField}를 키로 문면을 읽는다 — ID가 아니라 필드명이 안정적이기 때문이다. */
public final class ProseCatalog {

    private static final String RESOURCE = "/subtask/jangnyang-prose-v5.json";

    private ProseCatalog() { }

    public static Map<String, ProseEntry> load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ProseCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("문면 리소스가 없다: " + RESOURCE);
            JsonNode root = mapper.readTree(in);
            return mapper.convertValue(root.get("entries"),
                    new TypeReference<Map<String, ProseEntry>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("문면을 읽지 못했다: " + RESOURCE, e);
        }
    }
}
```

```java
// SesSubtaskDerivation.java
package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.SubtaskGroup;
import com.wastesim.subtask.SubtaskStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SES 가지치기 지점에서 서브태스크 세트를 만든다.
 *
 * <p><b>여기가 가설이 주장하는 자리다.</b> 지금까지 34문항은 사람이 리소스 파일에 적은
 * 목록이었다. 이 클래스가 하는 일은 그 목록이 트리에서 나오게 하는 것이고, 나온 것과
 * 적힌 것을 대조하는 것이 검증이다({@code DerivedSetVsV4ReportTest}).
 *
 * <p>유도하는 것과 사람이 쓰는 것의 경계: {@code answerType}·{@code allowedRange}·
 * {@code required}·{@code group}은 여기서 나오고, {@code question}·{@code validationRule}·
 * {@code basis}는 문면 파일에서 온다.
 */
public final class SesSubtaskDerivation {

    /** 화면 단계 이름 — 지점이 달린 엔티티가 루트 아래 어느 가지에 있는지로 정한다. */
    private static final List<String> GROUP_NAMES = List.of(
            "대상 시스템", "실험", "관측", "실행과 절차");

    private SesSubtaskDerivation() { }

    public static List<SubtaskSkeleton> deriveSkeletons() {
        EntityStructure structure = JangnyangEntityStructure.get();
        Map<String, DecisionPoint> byId = new LinkedHashMap<>();
        for (DecisionPoint p : DecisionPointExtractor.extract(structure)) byId.put(p.id(), p);

        List<SubtaskSkeleton> out = new ArrayList<>();
        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            DecisionPoint point = byId.get(b.pointId());
            if (point == null) {
                throw new IllegalStateException("SES에 없는 지점을 가리키는 대응: " + b.pointId());
            }
            out.add(new SubtaskSkeleton(b.pointId(), b.answerField(), b.answerType(),
                    rangeFor(point, b), isRequired(point), groupOf(structure, point)));
        }
        return List.copyOf(out);
    }

    /**
     * spec 축이면 허용값을 <b>트리에서</b> 채운다. 그 외에는 선언된 범위를 그대로 쓴다.
     * 이 한 줄이 "구조가 허용값을 정한다"의 실체다.
     */
    private static AllowedRange rangeFor(DecisionPoint point, SesFieldMapping.FieldBinding b) {
        AllowedRange declared = b.range();
        if (point instanceof DecisionPoint.SpecChoice spec) {
            return new AllowedRange(declared.description(), null, null, null, null, null, null, null,
                    spec.options());
        }
        return declared;
    }

    private static boolean isRequired(DecisionPoint point) {
        return point instanceof DecisionPoint.SpecChoice
                || point instanceof DecisionPoint.MultiCount;
    }

    /** 루트 바로 아래 가지 이름으로 화면 단계를 정한다. 닿지 않으면 마지막 그룹에 둔다. */
    private static int groupOf(EntityStructure structure, DecisionPoint point) {
        try {
            List<String> path = structure.pathTo(point.entity());
            if (path.size() >= 2) {
                int idx = GROUP_NAMES.indexOf(path.get(1));
                if (idx >= 0) return idx + 1;
            }
        } catch (IllegalArgumentException ignored) {
            // 커플링 활성 지점은 엔티티 경로로 닿지 않는다 — 아래 기본값으로 간다.
        }
        return GROUP_NAMES.size();
    }

    public static JangnyangSubtaskDefinition derive(Map<String, ProseEntry> prose) {
        List<JangnyangSubtask> subtasks = new ArrayList<>();
        int order = 1;

        for (SubtaskSkeleton s : deriveSkeletons()) {
            ProseEntry p = require(prose, s.answerField());
            subtasks.add(new JangnyangSubtask("ST-S" + pad(order), order, s.group(),
                    SubtaskStage.COLLECT, p.question(), s.answerField(), s.answerType(),
                    s.required(), p.allowsNotApplicable(), s.allowedRange(), p.validationRule(),
                    p.retryQuestion(), p.completionCondition(), p.basis()));
            order++;
        }

        // SES 밖 결정과 절차 제어. 트리에서 나오지 않지만 물어야 하는 것들이라,
        // 마지막 그룹에 모아 둔다 — 어디서 왔는지가 순서에 드러나야 한다.
        for (SesFieldMapping.NonSesField f : SesFieldMapping.nonSesFields()) {
            ProseEntry p = require(prose, f.answerField());
            subtasks.add(new JangnyangSubtask("ST-S" + pad(order), order, GROUP_NAMES.size(),
                    SubtaskStage.COLLECT, p.question(), f.answerField(), nonSesType(f),
                    true, p.allowsNotApplicable(), nonSesRange(f), p.validationRule(),
                    p.retryQuestion(), p.completionCondition(), p.basis()));
            order++;
        }

        List<SubtaskGroup> groups = new ArrayList<>();
        for (int i = 0; i < GROUP_NAMES.size(); i++) {
            groups.add(new SubtaskGroup(i + 1, GROUP_NAMES.get(i),
                    GROUP_NAMES.get(i) + "에 관한 결정을 받습니다."));
        }

        return new JangnyangSubtaskDefinition("jangnyang-simulator-v5", 5, true,
                groups, List.copyOf(subtasks));
    }

    private static ProseEntry require(Map<String, ProseEntry> prose, String field) {
        ProseEntry p = prose.get(field);
        if (p == null) throw new IllegalStateException("문면이 없는 필드: " + field);
        return p;
    }

    private static String pad(int order) {
        return String.format("%03d", order);
    }

    private static AnswerType nonSesType(SesFieldMapping.NonSesField f) {
        return switch (f.answerField()) {
            case "engine" -> AnswerType.ENUM;
            case "simulationGoal" -> AnswerType.STRING;
            default -> AnswerType.BOOLEAN;
        };
    }

    private static AllowedRange nonSesRange(SesFieldMapping.NonSesField f) {
        return switch (f.answerField()) {
            case "engine" -> new AllowedRange("java 또는 python", null, null, null, null, null,
                    null, null, List.of("java", "python"));
            case "simulationGoal" -> new AllowedRange("2자 이상 200자 이하의 한 문장", null, null,
                    2, 200, null, null, null, null);
            default -> new AllowedRange("예/아니오", null, null, null, null, null, null, null, null);
        };
    }
}
```

`SubtaskGroup`의 생성자 인자 순서는 실제 record 정의를 열어 맞춘다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./mvnw test -Dtest=SesSubtaskDerivationTest`
Expected: PASS (4건)

`derivedSetIsFullySpecifiedAndComplete`가 34가 아닌 수로 깨지면 그 수를 적어 둔다 — Task 7이 다룬다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/wastesim/ses src/main/resources/subtask/jangnyang-prose-v5.json docs/research/s1-ses-extraction/gen_prose_v5.py src/test/java/com/wastesim/ses/SesSubtaskDerivationTest.java
git commit -m "feat(ses): 문항 세트를 SES 가지치기 지점에서 유도한다"
```

---

### Task 7: 유도본과 v4를 대조한다 — 측정 1

**이 태스크의 산출물은 통과하는 테스트가 아니라 보고서다.** 차이가 없을 것으로 보지 않는다.

**Files:**
- Create: `src/test/java/com/wastesim/ses/DerivedSetVsV4ReportTest.java`
- Create: `docs/research/s1-ses-extraction/유도본-v4-대조.md` (Step 3이 손으로 쓴다)
- Modify: `src/main/java/com/wastesim/ses/SesFieldMapping.java` (Step 4에서 필요하면)

**Interfaces:**
- Consumes: Task 6의 `SesSubtaskDerivation` · 기존 `JangnyangSubtaskCatalog`
- Produces: 없음 (보고 전용)

- [ ] **Step 1: 대조 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 측정 1 — 문항이 SES에서 나오는가.
 *
 * <p>완전히 같을 것으로 보지 않는다. 어긋나는 자리가 나오면 그것이 발견이고, 이 테스트가
 * 하는 일은 그것을 <b>눈에 보이게 만드는 것</b>이다. 그래서 실패 메시지에 목록을 다 찍는다.
 */
class DerivedSetVsV4ReportTest {

    /** v4를 가리키는 실제 조회 메서드 이름은 카탈로그를 열어 맞춘다. */
    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    @Test
    void derivedFieldsMatchV4Fields() {
        Set<String> derived = new TreeSet<>(SesSubtaskDerivation.derive(ProseCatalog.load())
                .subtasks().stream().map(JangnyangSubtask::answerField).toList());
        Set<String> existing = new TreeSet<>(v4().subtasks().stream()
                .map(JangnyangSubtask::answerField).toList());

        Set<String> onlyDerived = new TreeSet<>(derived);
        onlyDerived.removeAll(existing);
        Set<String> onlyV4 = new TreeSet<>(existing);
        onlyV4.removeAll(derived);

        assertEquals(Set.of(), onlyDerived, "SES에 자리가 있는데 v4가 묻지 않던 것: " + onlyDerived);
        assertEquals(Set.of(), onlyV4, "v4가 묻는데 SES에 자리가 없는 것: " + onlyV4);
    }

    @Test
    void derivedAnswerTypesMatchV4() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> mismatches = SesSubtaskDerivation.derive(ProseCatalog.load()).subtasks().stream()
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> s.answerType() != existing.get(s.answerField()).answerType())
                .map(s -> s.answerField() + ": 유도=" + s.answerType()
                        + " v4=" + existing.get(s.answerField()).answerType())
                .sorted().toList();

        assertEquals(List.of(), mismatches, "자료형이 어긋난 문항: " + mismatches);
    }

    @Test
    void specChoiceOptionsMatchV4Values() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> mismatches = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.pointId().startsWith("spec:"))
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> !s.allowedRange().valuesOrEmpty()
                        .equals(existing.get(s.answerField()).allowedRange().valuesOrEmpty()))
                .map(s -> s.answerField() + ": 트리=" + s.allowedRange().valuesOrEmpty()
                        + " v4=" + existing.get(s.answerField()).allowedRange().valuesOrEmpty())
                .sorted().toList();

        assertEquals(List.of(), mismatches,
                "트리의 자식 이름과 v4의 허용값이 다르다 — 가설이 가장 강하게 걸린 자리다: " + mismatches);
    }
}
```

`JangnyangSubtaskCatalog`에 `byVersion(int)`가 없으면 실제 있는 조회 메서드로 바꾼다. 없으면 패키지 가시성 메서드를 하나 추가하되 **기존 동작은 건드리지 않는다.**

- [ ] **Step 2: 돌려서 차이를 본다**

Run: `./mvnw test -Dtest=DerivedSetVsV4ReportTest`
Expected: 세 건 중 일부 FAIL일 수 있다. **실패 메시지를 그대로 복사해 둔다.**

가장 가능성이 높은 차이:
- `truckType`의 허용값 — 트리는 `5톤 차량`, v4는 `5t` 같은 코드값일 수 있다
- `scenarioType` — 트리의 자식 이름은 한글(`수거시각 실험`), v4는 영문 키(`collection-sweep`)
- `collectionIntervalDays`·`occupationRatios`·`occupationMix`·`collectionNodes`·`residentsPerBuildingMap` 등 v4에만 있는 필드

- [ ] **Step 3: 차이를 보고서로 적는다**

`docs/research/s1-ses-extraction/유도본-v4-대조.md`에 쓴다. **테스트를 통과시키려고 트리나 대응을 먼저 고치지 않는다** — 먼저 적고, 어느 쪽이 옳은지 판단한 뒤에 고친다. 각 차이마다:

```markdown
## <필드명>

- 유도본: <값>
- v4: <값>
- 어느 쪽이 옳은가: <코드 근거와 함께>
- 조치: <트리를 고친다 / 대응을 고친다 / 문항을 뺀다 / 그대로 둔다>
```

**이름 표기 차이(한글 자식 이름 vs 영문 코드값)는 구조의 차이가 아니다.** 그 경우 조치는 "대응에 코드값 매핑을 더한다"이고, `SesFieldMapping.FieldBinding`에 `Map<String,String> optionCodes` 필드를 더해 트리의 자식 이름을 코드값으로 옮긴다. 이때도 **허용값의 개수와 순서는 여전히 트리가 정한다** — 이름만 옮긴다. 그러면 `SesSubtaskDerivation.rangeFor(...)`가 `spec.options()`를 코드값으로 매핑한 목록을 넣는다.

- [ ] **Step 4: 판단한 조치를 적용하고 테스트를 통과시킨다**

Run: `./mvnw test -Dtest=DerivedSetVsV4ReportTest`
Expected: PASS (3건)

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/wastesim/ses/DerivedSetVsV4ReportTest.java docs/research/s1-ses-extraction/유도본-v4-대조.md src/main/java/com/wastesim/ses
git commit -m "test(ses): 유도한 문항과 손으로 적은 v4를 대조한다 — 측정 1"
```

---

### Task 8: PES와 가지치기

**Files:**
- Create: `src/main/java/com/wastesim/ses/PrunedStructure.java`
- Create: `src/main/java/com/wastesim/ses/SesPruner.java`
- Test: `src/test/java/com/wastesim/ses/SesPrunerTest.java`

**Interfaces:**
- Consumes: Task 4·5의 `DecisionPointExtractor` · `SesFieldMapping`
- Produces:
  - `record PrunedStructure(Map<String,String> chosenSpecs, Map<String,Integer> counts, Map<String,Object> attributeValues, Set<String> deadCouplings)`
    - `String chosen(String pointId)` · `int count(String pointId)` · `Object value(String pointId)` · `boolean isLive(String couplingPointId)`
  - `SesPruner.prune(Map<String,Object> answersByField)` → `PrunedStructure`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SesPrunerTest {

    private static Map<String, Object> answers() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenarioType", "단일 실행");
        a.put("dischargeTimeMode", "직업별 외출시각 기반");
        a.put("travelTimeMode", "구간 상수");
        a.put("truckType", "5톤 차량");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 20);
        a.put("truckCount", 1);
        a.put("capacity", 60.0);
        a.put("trafficMode", "APPLY");
        return a;
    }

    @Test
    void specAnswerFoldsTheAxisToOneChild() {
        assertEquals("5톤 차량", SesPruner.prune(answers()).chosen("spec:수거차량:차종 축"));
    }

    @Test
    void multiAnswerSetsReplicationCount() {
        assertEquals(10, SesPruner.prune(answers()).count("multi:수거지점 집합"));
    }

    @Test
    void attributeAnswerBindsValue() {
        assertEquals(60.0, SesPruner.prune(answers()).value("attr:폐기물 유형:용량"));
    }

    @Test
    void unansweredAttributeIsAbsentNotNull() {
        assertNull(SesPruner.prune(answers()).value("attr:실험:기간"),
                "답하지 않은 속성은 PES에 넣지 않는다 — 넣지 않은 것이 곧 기록이다");
    }

    @Test
    void trafficOffKillsBothTrafficCouplings() {
        Map<String, Object> off = new LinkedHashMap<>(answers());
        off.put("trafficMode", "NONE");
        PrunedStructure pes = SesPruner.prune(off);
        assertFalse(pes.isLive("coupling:교통 구역.혼잡계수->수거 경로.이동시간"));
        assertFalse(pes.isLive("coupling:교통 구역.혼잡계수->교통혼잡 판정.판정"));
    }

    @Test
    void trafficOnKeepsThemAlive() {
        assertTrue(SesPruner.prune(answers())
                .isLive("coupling:교통 구역.혼잡계수->수거 경로.이동시간"));
    }

    @Test
    void unansweredSpecAxisFailsLoudly() {
        Map<String, Object> missing = new LinkedHashMap<>(answers());
        missing.remove("truckType");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SesPruner.prune(missing));
        assertTrue(e.getMessage().contains("차종 축"),
                "어느 축이 안 정해졌는지 말해야 한다: " + e.getMessage());
    }

    @Test
    void answerOutsideAxisOptionsFailsLoudly() {
        Map<String, Object> bogus = new LinkedHashMap<>(answers());
        bogus.put("truckType", "10톤 화물열차");
        assertThrows(IllegalStateException.class, () -> SesPruner.prune(bogus));
    }
}
```

테스트의 spec 답변 값은 **트리의 자식 이름**이다. Task 7에서 코드값 매핑을 더했다면 그 코드값으로 바꾼다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=SesPrunerTest`
Expected: FAIL — 컴파일 오류

- [ ] **Step 3: PES와 가지치기를 만든다**

```java
// PrunedStructure.java
package com.wastesim.ses;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 가지치기된 트리 — <b>이 사용자의 요청이 SES의 어느 가지를 골랐는가</b>.
 *
 * <p>{@code SimulationConfig}는 숫자 뭉치라 "원한 대로 구성됐는가"를 보기 어렵다. 이쪽은
 * 고른 가지가 그대로 보인다. 미리보기 화면과 가설 판정이 둘 다 이것을 읽는다.
 */
public record PrunedStructure(Map<String, String> chosenSpecs,
                              Map<String, Integer> counts,
                              Map<String, Object> attributeValues,
                              Set<String> deadCouplings) {

    public PrunedStructure {
        chosenSpecs = new LinkedHashMap<>(chosenSpecs);
        counts = new LinkedHashMap<>(counts);
        attributeValues = new LinkedHashMap<>(attributeValues);
        deadCouplings = new LinkedHashSet<>(deadCouplings);
    }

    public String chosen(String pointId) {
        return chosenSpecs.get(pointId);
    }

    public int count(String pointId) {
        Integer n = counts.get(pointId);
        if (n == null) throw new IllegalStateException("복제 수가 정해지지 않았다: " + pointId);
        return n;
    }

    public Object value(String pointId) {
        return attributeValues.get(pointId);
    }

    /** 조건이 없는 결합은 언제나 살아 있다 — 죽은 목록에 없으면 산 것이다. */
    public boolean isLive(String couplingId) {
        return !deadCouplings.contains(couplingId);
    }
}
```

```java
// SesPruner.java
package com.wastesim.ses;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 답변으로 트리를 접는다. spec 축은 하나로, multi는 개수로, 속성은 값으로.
 *
 * <p><b>조용히 넘어가지 않는다.</b> spec 축이나 복제 수가 안 정해지면 예외를 던진다 —
 * 그 자리가 비면 모델을 만들 수 없는데, 기본값으로 메우면 사용자가 고르지 않은 가지가
 * 결과에 들어가 있고 아무 데도 그 사실이 남지 않는다.
 */
public final class SesPruner {

    private SesPruner() { }

    public static PrunedStructure prune(Map<String, Object> answersByField) {
        Map<String, DecisionPoint> byId = new LinkedHashMap<>();
        for (DecisionPoint p : DecisionPointExtractor.extract(JangnyangEntityStructure.get())) {
            byId.put(p.id(), p);
        }

        Map<String, String> chosen = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> dead = new LinkedHashSet<>();

        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            DecisionPoint point = byId.get(b.pointId());
            Object answer = answersByField.get(b.answerField());

            if (point instanceof DecisionPoint.SpecChoice spec) {
                if (answer == null) {
                    throw new IllegalStateException("spec 축이 정해지지 않았다: " + spec.axis()
                            + " (필드 " + b.answerField() + ")");
                }
                String picked = String.valueOf(answer);
                if (!spec.options().contains(picked)) {
                    throw new IllegalStateException("축에 없는 가지를 골랐다: " + spec.axis()
                            + " ← " + picked + " (가능: " + spec.options() + ")");
                }
                chosen.put(spec.id(), picked);

            } else if (point instanceof DecisionPoint.MultiCount multi) {
                if (answer == null) {
                    throw new IllegalStateException("복제 수가 정해지지 않았다: " + multi.entity()
                            + " (필드 " + b.answerField() + ")");
                }
                counts.put(multi.id(), ((Number) answer).intValue());

            } else if (point instanceof DecisionPoint.CouplingActivation) {
                // 답이 없으면 켠 것으로 보지 않는다 — 끄는 쪽이 안전한 기본이다.
                if (!"APPLY".equals(String.valueOf(answer))) killAllCouplingPoints(dead, byId);

            } else if (answer != null) {
                // 답하지 않은 속성은 PES에 넣지 않는다 — 넣지 않은 것이 곧
                // "이 실험에서 정하지 않았다"는 기록이다.
                values.put(point.id(), answer);
            }
        }

        return new PrunedStructure(chosen, counts, values, dead);
    }

    /**
     * 교통을 끄면 교통 구역에서 나가는 결합이 <b>전부</b> 죽는다. 지금 조건부 결합은 둘
     * 다 같은 조건({@code trafficMode=APPLY})을 갖는다 — 조건이 갈라지면 이 자리를
     * 조건별로 나눈다.
     */
    private static void killAllCouplingPoints(Set<String> dead, Map<String, DecisionPoint> byId) {
        List<String> activations = byId.keySet().stream()
                .filter(id -> id.startsWith("coupling:")).toList();
        dead.addAll(activations);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=SesPrunerTest`
Expected: PASS (8건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/ses/PrunedStructure.java src/main/java/com/wastesim/ses/SesPruner.java src/test/java/com/wastesim/ses/SesPrunerTest.java
git commit -m "feat(ses): 답변으로 트리를 가지치기해 PES를 만든다"
```

---

### Task 9: PES를 SimulationConfig로 편다

**Files:**
- Create: `src/main/java/com/wastesim/ses/PesFlattener.java`
- Test: `src/test/java/com/wastesim/ses/PesFlattenerTest.java`

**Interfaces:**
- Consumes: Task 8의 `PrunedStructure` · `SesPruner`
- Produces: `PesFlattener.flatten(PrunedStructure pes)` → `SimulationConfig`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PesFlattenerTest {

    private static SimulationConfig flatten(Map<String, Object> answers) {
        return PesFlattener.flatten(SesPruner.prune(answers));
    }

    private static Map<String, Object> base() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenarioType", "단일 실행");
        a.put("dischargeTimeMode", "직업별 외출시각 기반");
        a.put("travelTimeMode", "구간 상수");
        a.put("truckType", "5톤 차량");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 20);
        a.put("truckCount", 2);
        a.put("trafficMode", "NONE");
        return a;
    }

    @Test
    void countsBecomeConfigNumbers() {
        SimulationConfig c = flatten(base());
        assertEquals(10, c.getNumBuildings());
        assertEquals(20, c.getResidentsPerBuilding());
        assertEquals(2, c.getNumTrucks());
    }

    @Test
    void unansweredAttributeKeepsConfigDefault() {
        assertEquals(new SimulationConfig().getDays(), flatten(base()).getDays(),
                "답하지 않은 값은 SimulationConfig의 기본값 그대로여야 한다");
    }

    @Test
    void deadTrafficCouplingDisablesTraffic() {
        assertFalse(flatten(base()).isTrafficEnabled());
    }
}
```

`SimulationConfig`의 실제 getter 이름이 다르면(`isTrafficEnabled`·`getNumTrucks` 등) 실제 이름으로 맞춘다 — **`SimulationConfig`는 수정하지 않는다.**

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=PesFlattenerTest`
Expected: FAIL — 컴파일 오류 (`PesFlattener`가 없다)

- [ ] **Step 3: 평탄화를 만든다**

`JangnyangScenarioBuilder.toConfig()`(`:167` 이하)를 **열어 놓고** 옮긴다. 새로 지어내지 않는다 — 그 메서드와 한 줄씩 같아야 Task 10의 차등 테스트가 통과한다. 특히 이 넷을 그대로 옮긴다:

1. `collectionSchedule` enum → `setCollectionIntervalDays` 또는 요일 집합 (**둘 중 하나만**)
2. `occupationPreset` → `ScenarioPreset.fromKey(...).mix`를 `setOccupationMix`로
3. `collectionTimes`가 **2개 이상일 때만** `setCollectionTimesMinutes`
4. `dischargeWindow`의 `[시작분, 종료분]`을 각각 다른 세터로

```java
package com.wastesim.ses;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.SimulationConfig;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * 가지치기된 트리를 계산용 설정으로 편다.
 *
 * <p><b>{@code JangnyangScenarioBuilder.toConfig()}와 결과가 같아야 한다.</b> 그 메서드는
 * 지우지 않고 참조 구현으로 남겨 두었고, {@code PesFlattenerDifferentialTest}가 둘을
 * 필드 단위로 대조한다. 여기서 새 규칙을 지어내면 조용히 다른 시뮬레이션이 된다.
 *
 * <p>답하지 않은 값은 세터를 부르지 않는다 — 부르지 않은 자리가 곧 {@code SimulationConfig}의
 * 기본값이고, 그것이 "이 실험에서 정하지 않았다"는 기록이다.
 */
public final class PesFlattener {

    private PesFlattener() { }

    public static SimulationConfig flatten(PrunedStructure pes) {
        SimulationConfig c = new SimulationConfig();

        // ── 복제 수 ──────────────────────────────────────────────────────────
        count(pes, "multi:수거지점 집합", c::setNumBuildings);
        count(pes, "multi:거주민 집합", c::setResidentsPerBuilding);
        count(pes, "multi:수거차량 집합", c::setNumTrucks);

        // ── 직접 대응하는 속성 ───────────────────────────────────────────────
        intOf(pes, "attr:실험:기간", c::setDays);
        intOf(pes, "attr:실험:반복횟수", c::setSeeds);
        dblOf(pes, "attr:대상 시스템:1인배출량", c::setWasteMeanKg);
        dblOf(pes, "attr:대상 시스템:배출량변동", c::setWasteSigma);
        dblOf(pes, "attr:거주민:외출시각변동", c::setLeaveSigma);
        dblOf(pes, "attr:폐기물 유형:용량", c::setCapacity);
        dblOf(pes, "attr:폐기물 유형:임계값", c::setThreshold);
        dblOf(pes, "attr:수거차량:적재용량", c::setRouteAvailableCapacityKg);
        dblOf(pes, "attr:수거차량:초기적재량", c::setInitialTruckLoadKg);
        intOf(pes, "attr:수거차량:배차간격", c::setDispatchIntervalMinutes);
        intOf(pes, "attr:수거차량:지점당수거시간", c::setServiceMinutesPerSite);
        intOf(pes, "attr:구간 상수:구간이동시간", c::setRouteTravelMinutes);
        intOf(pes, "attr:교통구역 근사:구역내이동시간", c::setIntraZoneTravelMinutes);
        strOf(pes, "attr:교통 구역:시간대프로파일", c::setTrafficProfileId);
        strOf(pes, "attr:교통구역 근사:구역배정가정", c::setZoneAssignmentRule);

        // ── 고른 가지 ────────────────────────────────────────────────────────
        chosen(pes, "spec:수거차량:차종 축", c::setTruckType);
        chosen(pes, "spec:수거 경로:이동시간 방식 축", c::setTravelTimeMode);
        chosen(pes, "spec:거주민:배출시각 모델 축", c::setDischargeTimeMode);

        // ── 죽은 결합 ────────────────────────────────────────────────────────
        c.setTrafficEnabled(pes.isLive("coupling:교통 구역.혼잡계수->수거 경로.이동시간"));

        // ── 분기가 필요한 것들 — toConfig()의 규칙을 그대로 옮긴다 ────────────
        applyCollectionTime(pes, c);
        applyCollectionSchedule(pes, c);
        applyOccupationPreset(pes, c);
        applyDischargeWindow(pes, c);

        return c;
    }

    /**
     * 수거 시각은 한 지점({@code attr:수거차량:수거시각})에 단일값과 목록이 함께 걸려 있다.
     * 목록이면 2개 이상일 때만 다회 수거로 보고, 아니면 단일 시각으로 본다 — toConfig()가
     * 그렇게 한다.
     */
    private static void applyCollectionTime(PrunedStructure pes, SimulationConfig c) {
        Object v = pes.value("attr:수거차량:수거시각");
        if (v instanceof Number n) {
            c.setCollectionTimeMinutes(n.intValue());
        } else if (v instanceof List<?> list && list.size() > 1) {
            List<Integer> minutes = list.stream()
                    .filter(Number.class::isInstance).map(o -> ((Number) o).intValue()).toList();
            if (!minutes.isEmpty()) c.setCollectionTimesMinutes(minutes);
        }
    }

    private static void applyCollectionSchedule(PrunedStructure pes, SimulationConfig c) {
        Object schedule = pes.value("attr:수거차량:수거요일");
        if (schedule == null) return;
        switch (String.valueOf(schedule)) {
            case "EVERY_DAY" -> c.setCollectionIntervalDays(1);
            case "EVERY_2_DAYS" -> c.setCollectionIntervalDays(2);
            case "EVERY_3_DAYS" -> c.setCollectionIntervalDays(3);
            case "EVERY_7_DAYS" -> c.setCollectionIntervalDays(7);
            case "WEEKDAYS_MON_FRI" -> c.setCollectionDaysOfWeek(List.of(0, 1, 2, 3, 4));
            case "MON_WED_FRI" -> c.setCollectionDaysOfWeek(List.of(0, 2, 4));
            case "POHANG_MON_TUE_THU_FRI" -> c.setCollectionDaysOfWeek(List.of(0, 1, 3, 4));
            default -> throw new IllegalStateException("세트에 없는 수거 스케줄 값: " + schedule);
        }
    }

    private static void applyOccupationPreset(PrunedStructure pes, SimulationConfig c) {
        Object preset = pes.value("attr:거주민 집합:직업구성");
        if (preset == null) return;
        c.setOccupationMix(List.copyOf(ScenarioPreset.fromKey(String.valueOf(preset)).mix));
    }

    private static void applyDischargeWindow(PrunedStructure pes, SimulationConfig c) {
        Object window = pes.value("attr:포항시 배출시간대 기반:배출허용창");
        if (!(window instanceof List<?> list) || list.size() != 2) return;
        if (list.get(0) instanceof Number start && list.get(1) instanceof Number end) {
            c.setDischargeWindowStartMinutes(start.intValue());
            c.setDischargeWindowEndMinutes(end.intValue());
        }
    }

    private static void count(PrunedStructure pes, String pointId, IntConsumer setter) {
        Integer n = pes.counts().get(pointId);
        if (n != null) setter.accept(n);
    }

    private static void chosen(PrunedStructure pes, String pointId, Consumer<String> setter) {
        String picked = pes.chosen(pointId);
        if (picked != null) setter.accept(picked);
    }

    private static void intOf(PrunedStructure pes, String pointId, IntConsumer setter) {
        if (pes.value(pointId) instanceof Number n) setter.accept(n.intValue());
    }

    private static void dblOf(PrunedStructure pes, String pointId, DoubleConsumer setter) {
        if (pes.value(pointId) instanceof Number n) setter.accept(n.doubleValue());
    }

    private static void strOf(PrunedStructure pes, String pointId, Consumer<String> setter) {
        Object v = pes.value(pointId);
        if (v != null) setter.accept(String.valueOf(v));
    }
}
```

`setCollectionDaysOfWeek`·`setServiceMinutesPerSite`·`setZoneAssignmentRule`의 실제 세터 이름과 인자 타입은 `SimulationConfig`에서 확인해 맞춘다. 빌더의 `applyDaysOfWeek(...)`가 private 헬퍼라면 그것이 실제로 무엇을 호출하는지 읽고 같은 세터를 부른다. `setTruckType`·`setTravelTimeMode`·`setDischargeTimeMode`가 문자열이 아닌 enum을 받으면 트리의 자식 이름을 Task 7에서 더한 코드값으로 옮긴 뒤 변환한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./mvnw test -Dtest=PesFlattenerTest`
Expected: PASS (3건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/wastesim/ses/PesFlattener.java src/test/java/com/wastesim/ses/PesFlattenerTest.java
git commit -m "feat(ses): 가지치기된 트리를 계산용 설정으로 편다"
```

---

### Task 10: 차등 테스트 — 측정 2

**Files:**
- Create: `src/test/java/com/wastesim/ses/ReferenceConfigPath.java`
- Create: `src/test/java/com/wastesim/ses/PesFlattenerDifferentialTest.java`
- Modify: `src/main/java/com/wastesim/ses/PesFlattener.java` (차이가 나오면)

**Interfaces:**
- Consumes: Task 9의 `PesFlattener.flatten(PrunedStructure)` · 기존 `JangnyangScenarioBuilder`
- Produces: 없음

- [ ] **Step 1: 참조 경로 어댑터의 뼈대를 만든다**

`toConfig()`는 private이고 `JangnyangSubtaskAnswer` 맵을 받는다. 테스트가 그것을 부를 수 있게 얇은 어댑터를 **테스트 소스에** 둔다 — 제품 코드의 가시성을 넓히지 않는다.

```java
// src/test/java/com/wastesim/ses/ReferenceConfigPath.java
package com.wastesim.ses;

import com.wastesim.model.SimulationConfig;

import java.util.Map;

/**
 * 기존 경로({@code JangnyangScenarioBuilder})로 설정을 만든다 — 차등 테스트의 기준.
 *
 * <p>제품 코드의 가시성을 넓히지 않으려고 테스트 쪽에 둔다. 필드 맵을
 * {@code JangnyangSubtaskAnswer} 맵으로 바꿔 빌더에 넘기고, 빌더가 돌려준
 * {@code BuildOutcome}에서 설정을 꺼낸다.
 */
final class ReferenceConfigPath {

    private ReferenceConfigPath() { }

    static SimulationConfig build(Map<String, Object> answersByField) {
        throw new UnsupportedOperationException("Step 2에서 채운다");
    }
}
```

- [ ] **Step 2: 어댑터를 실제로 채운다**

이 순서로 읽고 쓴다:

1. `JangnyangScenarioBuilder`의 생성자 인자(`JangnyangCompletenessChecker` 등)를 확인하고, 테스트에서 실물로 조립한다
2. `JangnyangSubtaskAnswer`의 생성자를 확인하고, `answersByField`의 각 항목을 v4 세트의 서브태스크 id에 묶어 맵을 만든다 (`def.byAnswerField(field).id()`가 id를 준다)
3. `build(def, answers, ...)`를 부르고 `BuildOutcome`에서 `SimulationConfig`를 꺼낸다

빌더가 필수 답변을 요구해 막히면 **빌더를 고치지 않고** 차등 비교에 필요한 최소 답변(`simulationGoal`·`engine`·승인 3종)을 Step 3의 `answers(...)`에 더한다.

- [ ] **Step 3: 두 경로를 대조하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 측정 2 — SES 경로만으로 같은 시뮬레이션을 구성할 수 있는가.
 *
 * <p>숫자가 아니라 {@link SimulationConfig}를 비교한다. 결과를 비교하면 "다르다"까지만
 * 알 수 있지만 설정을 비교하면 <b>어느 필드가</b> 다른지 바로 나온다.
 */
class PesFlattenerDifferentialTest {

    /** 답변 조합. 분기가 있는 필드는 값을 바꿔 가며 모든 갈래를 밟는다. */
    private static List<Map<String, Object>> answerSets() {
        return List.of(
                answers(m -> { }),
                answers(m -> m.put("collectionSchedule", "EVERY_2_DAYS")),
                answers(m -> m.put("collectionSchedule", "WEEKDAYS_MON_FRI")),
                answers(m -> m.put("collectionSchedule", "MON_WED_FRI")),
                answers(m -> m.put("collectionSchedule", "POHANG_MON_TUE_THU_FRI")),
                answers(m -> m.put("travelTimeMode", "구간 상수")),
                answers(m -> m.put("travelTimeMode", "교통구역 근사")),
                answers(m -> m.put("trafficMode", "APPLY")),
                answers(m -> m.put("collectionTimes", List.of(360, 1080))),
                answers(m -> m.put("dischargeWindow", List.of(1200, 360))),
                answers(m -> m.put("occupationPreset", "university"))
        );
    }

    private static Map<String, Object> answers(Consumer<Map<String, Object>> tweak) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenarioType", "단일 실행");
        a.put("dischargeTimeMode", "직업별 외출시각 기반");
        a.put("travelTimeMode", "구간 상수");
        a.put("truckType", "5톤 차량");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 20);
        a.put("truckCount", 1);
        a.put("trafficMode", "NONE");
        a.put("days", 7);
        a.put("capacity", 60.0);
        a.put("threshold", 0.8);
        tweak.accept(a);
        return a;
    }

    @Test
    void sesPathProducesTheSameConfigAsTheBuilder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Map<String, Object> answers : answerSets()) {
            SimulationConfig viaSes = PesFlattener.flatten(SesPruner.prune(answers));
            SimulationConfig viaBuilder = ReferenceConfigPath.build(answers);
            assertEquals(mapper.writeValueAsString(viaBuilder), mapper.writeValueAsString(viaSes),
                    "답변 " + answers + " 에서 두 경로의 설정이 다르다");
        }
    }
}
```

`occupationPreset`의 `"university"`는 `ScenarioPreset`의 실제 키로 맞춘다.

- [ ] **Step 4: 돌려서 차이를 본다**

Run: `./mvnw test -Dtest=PesFlattenerDifferentialTest`
Expected: 처음에는 FAIL 가능. 실패 메시지가 어느 필드가 다른지 보여 준다.

차이가 나오면 **`PesFlattener`를 고친다.** 빌더 쪽을 고쳐서 맞추지 않는다 — 기준은 기존 동작이다.

- [ ] **Step 5: 통과를 확인하고 전체 테스트를 돌린다**

Run: `./mvnw test`
Expected: 전부 PASS (골든 3건 포함)

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/wastesim/ses src/main/java/com/wastesim/ses/PesFlattener.java
git commit -m "test(ses): SES 경로와 기존 빌더가 같은 설정을 만드는지 대조한다 — 측정 2"
```

---

### Task 11: 세트 v5를 카탈로그에 등록하고 PES를 명세에 싣는다

여기까지 오면 유도된 세트와 SES 경로가 둘 다 검증돼 있다. 실제로 쓰이게 연결한다. **`toConfig()`는 아직 지우지 않는다** — 차등 테스트의 기준이다.

**Files:**
- Modify: `src/main/java/com/wastesim/subtask/JangnyangSubtaskCatalog.java`
- Modify: `src/main/java/com/wastesim/subtask/JangnyangScenarioSpec.java`
- Modify: `src/main/java/com/wastesim/subtask/JangnyangScenarioBuilder.java`
- Test: `src/test/java/com/wastesim/ses/SesPathWiringTest.java`

**Interfaces:**
- Consumes: Task 6·8의 `SesSubtaskDerivation` · `SesPruner`
- Produces: `JangnyangScenarioSpec.prunedStructure()` → `PrunedStructure` (없으면 `null`)

- [ ] **Step 1: 연결을 확인하는 테스트를 쓴다**

```java
package com.wastesim.ses;

import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SesPathWiringTest {

    @Test
    void latestSetIsTheDerivedV5() {
        JangnyangSubtaskDefinition latest = new JangnyangSubtaskCatalog().latest();
        assertEquals("jangnyang-simulator-v5", latest.subtaskSetId());
        assertEquals(34, latest.subtasks().size());
    }

    @Test
    void v4IsStillAvailableForOldSessions() {
        assertNotNull(new JangnyangSubtaskCatalog().byVersion(4),
                "진행 중이던 세션이 무엇을 물어서 받은 답인지 알 수 없게 되면 안 된다");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./mvnw test -Dtest=SesPathWiringTest`
Expected: FAIL — `latest()`가 아직 v4를 준다

- [ ] **Step 3: 카탈로그에 v5를 등록한다**

v5는 리소스 파일이 아니라 **유도로 만들어진다.** 카탈로그 생성자가 리소스들을 읽은 뒤 `SesSubtaskDerivation.derive(ProseCatalog.load())`를 `byVersion` 맵에 넣는다. `SET_RESOURCES` 배열은 건드리지 않는다 — v2~v4는 여전히 파일에서 온다.

주석을 남긴다:

```java
// v5는 파일이 아니라 SES 트리에서 유도한다. 여기가 "질문을 서버가 소유한다"(D-44)의
// 다음 단계다 — 질문의 출처가 리소스 파일에서 구조 자체로 옮겨 간다. 문면은 여전히
// 파일(jangnyang-prose-v5.json)에 있으므로 말을 고치는 데 재컴파일은 필요 없다.
```

기동 시점 규약 검사(항목 누락·order 중복)는 v5에도 **똑같이 적용한다** — 유도본이라고 봐주면 잘못된 세트로 서버가 뜬다.

- [ ] **Step 4: 빌더가 PES를 만들어 명세에 붙이게 한다**

`JangnyangScenarioBuilder.build(...)`에서 `toConfig()`를 부른 **뒤에** 같은 답변으로 `SesPruner.prune(...)`을 부르고 결과를 `JangnyangScenarioSpec`에 담는다. 설정은 여전히 `toConfig()`가 만든 것을 쓴다 — **이 태스크에서 계산 경로를 바꾸지 않는다.** PES는 미리보기에 보여 줄 기록으로만 붙인다.

가지치기가 예외를 던지면 삼키지 않고 그대로 올린다. 답이 빠졌다는 뜻이고, 그건 실행 전에 드러나야 한다.

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./mvnw test`
Expected: 전부 PASS

v5가 최신이 되면서 기존 테스트가 깨질 수 있다 — 세트 해시를 못 박은 테스트(UT-299 계열)와 "최신 세트는 v4"를 가정한 테스트다. **깨진 테스트가 무엇을 지키던 것인지 읽고** 고친다. 해시 고정 테스트는 v4를 명시적으로 가리키게 바꾼다(그 테스트가 지키려던 것은 "파일을 몰래 고칠 수 없다"이고, v5는 파일이 아니므로 대상이 아니다).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/wastesim/subtask src/test/java/com/wastesim/ses/SesPathWiringTest.java
git commit -m "feat(subtask): 유도한 v5를 최신 세트로 등록하고 PES를 명세에 싣는다"
```

---

## 1단계를 마치고 보고할 것

`docs/research/s1-ses-extraction/유도본-v4-대조.md`에 이 셋을 적는다. **이것이 1단계의 진짜 산출물이다.**

1. **측정 1 결과** — 유도한 문항과 v4가 몇 개 맞고 몇 개 어긋났는가. 어긋난 것은 어느 쪽이 옳았는가
2. **측정 2 결과** — 차등 테스트 답변 조합 몇 개가 통과했는가. `PesFlattener`를 몇 번 고쳐야 했는가
3. **유도의 강도** — spec 축 4개는 허용값까지 구조에서 나왔다. 속성 21개는 존재만 나왔다. 이 비율이 가설의 실제 강도다

## 2단계로 넘어가기 전 확인

- [ ] `./mvnw test` 전부 통과
- [ ] 골든 스냅샷 3개가 커밋돼 있다
- [ ] `toConfig()`가 아직 살아 있다 (지우지 않았다)
- [ ] `reference-ses.json`이 `src/main/resources` 아래에 **없다**
- [ ] `git diff` 기준 `SimulationEngine.java`·`SimulationConfig.java`가 변경되지 않았다
