# Test Design Document (TDD)

**Project:** Java MCP Server with LLM-Driven Argument Extraction
**Example Domain:** Calculator Simulation
**Version:** 1.0
**Date:** 2026-06-23
**Companion documents:** SRS.md, SDD.md

---

## 1. Introduction

### 1.1 Purpose

This document defines the **test design and plan** for the system specified in `SRS.md` and designed in `SDD.md`. It enumerates concrete, runnable test cases (unit and integration) with inputs, expected outputs, and the requirement each one verifies. It is detailed enough for a coding agent to generate the corresponding JUnit 5 tests.

### 1.2 Scope of Testing

In scope: keyword resolution, prompt selection, the `AiCaller` abstraction/swap, all calculator validation rules, the fail-closed readiness gate, calculator computation, and end-to-end pipeline behavior with a stubbed LLM.

Out of scope: live LLM provider quality, real network calculator API, load/performance benchmarking beyond NFR-1 smoke checks.

### 1.3 Test Levels

| Level | Target | Network |
|-------|--------|---------|
| Unit (UT) | single class in isolation, collaborators mocked | none |
| Integration (IT) | full pipeline via `SimulationOrchestrator` with `StubAiCaller` | none |
| Smoke (SM) | app boots, `tools/list` responds | none |

---

## 2. Test Approach & Tooling

- **Framework:** JUnit 5 (Jupiter) + AssertJ.
- **Mocking:** Mockito (`AiCaller`, `CalculatorApiClient`).
- **JSON:** Jackson `ObjectMapper` to build `JsonNode` arguments in tests.
- **Spring:** `@SpringBootTest` for IT with property `ai.provider=stub`.
- **Determinism:** No test calls a real LLM or network. `StubAiCaller` returns fixed `JsonNode`s.
- **Coverage target:** ≥ 90% lines on `routing`, `tool.calculator`, `orchestration`.

### 2.1 Test Data Builders

```java
static JsonNode args(String operation, double... operands) {
    ObjectNode n = mapper.createObjectNode();
    if (operation != null) n.put("operation", operation);
    ArrayNode arr = n.putArray("operands");
    for (double d : operands) arr.add(d);
    return n;
}
// args with NO operands array:
static JsonNode argsNoOperands(String operation) {
    ObjectNode n = mapper.createObjectNode();
    if (operation != null) n.put("operation", operation);
    return n;
}
```

---

## 3. Unit Tests

### 3.1 `SimulationTypeResolver` — UT-RES (FR-4..7)

| ID | Input text | Expected `SimulationType` | Verifies |
|----|-----------|---------------------------|----------|
| UT-RES-01 | `"3 plus 4"` | `CALCULATOR` | FR-4, FR-5 |
| UT-RES-02 | `"divide 10 by 2"` | `CALCULATOR` | FR-5 |
| UT-RES-03 | `"square root of 9"` | `CALCULATOR` | FR-5 (weighted phrase) |
| UT-RES-04 | `"3 더하기 4"` | `CALCULATOR` | FR-5 (Korean) |
| UT-RES-05 | `"PLUS"` (uppercase) | `CALCULATOR` | case-insensitive |
| UT-RES-06 | `"tell me a story"` | `UNKNOWN` | FR-6 |
| UT-RES-07 | `""` (empty) | `UNKNOWN` | FR-6, null-safety |
| UT-RES-08 | `null` | `UNKNOWN` | FR-6, null-safety |

```java
@Test void ut_res_01_plus_is_calculator() {
    assertThat(resolver.resolve("3 plus 4")).isEqualTo(SimulationType.CALCULATOR);
}
@Test void ut_res_06_unrelated_is_unknown() {
    assertThat(resolver.resolve("tell me a story")).isEqualTo(SimulationType.UNKNOWN);
}
@Test void ut_res_08_null_is_unknown() {
    assertThat(resolver.resolve(null)).isEqualTo(SimulationType.UNKNOWN);
}
```

### 3.2 `PromptRegistry` — UT-PR (FR-8, FR-9)

| ID | Action | Expected | Verifies |
|----|--------|----------|----------|
| UT-PR-01 | `specFor(CALCULATOR)` | non-blank `systemPrompt`, contains "JSON" and "Do NOT compute" | FR-8 |
| UT-PR-02 | `specFor(CALCULATOR).jsonSchema()` | valid JSON containing `"operation"` enum and `"operands"` | FR-9 |
| UT-PR-03 | `specFor(UNKNOWN)` | throws `IllegalArgumentException` | defensive design |

### 3.3 `AiCaller` abstraction & swap — UT-AI (FR-10, FR-22, FR-23, NFR-5)

| ID | Scenario | Expected | Verifies |
|----|----------|----------|----------|
| UT-AI-01 | `StubAiCaller.extractArguments(req)` returns preset JSON | `transportOk=true`, `arguments` non-null | FR-10 |
| UT-AI-02 | Orchestrator built with `StubAiCaller` then with a Mockito `AiCaller` — same pipeline code, no recompilation of controllers | both run | FR-22 |
| UT-AI-SWAP-01 | Spring context with `ai.provider=stub` | injected `AiCaller` is `StubAiCaller` | FR-23, NFR-5 |
| UT-AI-SWAP-02 | Spring context with `ai.provider=openai` (key mocked) | injected `AiCaller` is `OpenAiCaller` | FR-23 |
| UT-AI-03 | `AiResult` with `transportOk=false` | downstream treats as `ready:false` (see IT-PIPE-05) | FR-11 |

```java
@Test void ut_ai_swap_01_stub_selected() {
    // context property: ai.provider=stub
    assertThat(aiCaller).isInstanceOf(StubAiCaller.class);
}
```

### 3.4 `CalculatorController.validate()` — UT-VAL (FR-13..17, SRS §4.2)

Each row maps to a rule V-1..V-6. Expected `ready` and primary `ErrorCode` are listed.

| ID | `arguments` | Expected `ready` | Expected `ErrorCode` | Rule |
|----|-------------|------------------|----------------------|------|
| UT-VAL-01 | `{ADD,[3,4]}` | true | — | happy path |
| UT-VAL-02 | `{ADD,[1,2,3,4]}` | true | — | variadic add |
| UT-VAL-03 | `{SQRT,[9]}` | true | — | V-3 ok |
| UT-VAL-04 | `{operation:null,[3,4]}` (missing) | false | `MISSING_FIELD` | V-1 |
| UT-VAL-05 | `{"FOO",[3,4]}` | false | `INVALID_ENUM` | V-1 |
| UT-VAL-06 | `{ADD,[]}` (empty operands) | false | `EMPTY_FIELD` | V-2 |
| UT-VAL-07 | `{ADD}` (no operands key) | false | `EMPTY_FIELD` | V-2 |
| UT-VAL-08 | `{SQRT,[9,4]}` | false | `ARITY` | V-3 |
| UT-VAL-09 | `{SQRT,[-9]}` | false | `OUT_OF_RANGE` | V-3 |
| UT-VAL-10 | `{ADD,[5]}` (one operand) | false | `ARITY` | V-4 |
| UT-VAL-11 | `{DIVIDE,[10,0]}` | false | `DIVIDE_BY_ZERO` | V-5 |
| UT-VAL-12 | `{DIVIDE,[10,2,0]}` | false | `DIVIDE_BY_ZERO` | V-5 (any divisor) |
| UT-VAL-13 | `{ADD,[1e13,2]}` | false | `OUT_OF_RANGE` | V-6 |
| UT-VAL-14 | `{MULTIPLY,["x",2]}` (non-number) | false | `WRONG_TYPE` | V-6 |
| UT-VAL-15 | `{add,[3,4]}` (lowercase op) | true | — | case-normalization (V-1) |

```java
@Test void ut_val_11_divide_by_zero_blocks() {
    ValidationResult r = controller.validate(args("DIVIDE", 10, 0));
    assertThat(r.ready()).isFalse();
    assertThat(r.errors()).anyMatch(e -> e.code() == ErrorCode.DIVIDE_BY_ZERO);
}

@Test void ut_val_01_happy_path() {
    assertThat(controller.validate(args("ADD", 3, 4)).ready()).isTrue();
}
```

### 3.5 Readiness gate blocks external call — UT-GATE (FR-15, C3)

| ID | Scenario | Expected | Verifies |
|----|----------|----------|----------|
| UT-GATE-01 | `validate` returns `ready:false`; spy on `CalculatorService.compute` | `compute` is **never** called | FR-15, C3 |
| UT-GATE-02 | `validate` returns `ready:true`; then `execute` | `compute` called exactly once | FR-16 |

```java
@Test void ut_gate_01_no_call_when_not_ready() {
    CalculatorService svc = mock(CalculatorService.class);
    // orchestrator with controller whose validate() fails
    orchestrator.handle("divide 10 by 0");          // stub LLM returns {DIVIDE,[10,0]}
    verify(svc, never()).compute(any(), anyList()); // external API blocked
}
```

### 3.6 `CalculatorApiClient.calculate()` — UT-CALC (FR-19, FR-20)

| ID | `operation` / operands | Expected `result` | Verifies |
|----|------------------------|-------------------|----------|
| UT-CALC-01 | ADD [3,4] | 7.0 | FR-19 |
| UT-CALC-02 | ADD [1,2,3,4] | 10.0 | variadic |
| UT-CALC-03 | SUBTRACT [10,3,2] | 5.0 | left fold |
| UT-CALC-04 | MULTIPLY [2,3,4] | 24.0 | FR-19 |
| UT-CALC-05 | DIVIDE [20,2,5] | 2.0 | left fold |
| UT-CALC-06 | POWER [2,10] | 1024.0 | FR-19 |
| UT-CALC-07 | SQRT [9] | 3.0 | FR-19 |
| UT-CALC-08 | ADD [-5,5] | 0.0 | signed |

```java
@Test void ut_calc_06_power() {
    assertThat(client.calculate(Operation.POWER, List.of(2.0, 10.0)))
        .isEqualTo(1024.0);
}
```

---

## 4. Integration Tests (full pipeline, stubbed LLM) — IT-PIPE

Run with `@SpringBootTest(properties = "ai.provider=stub")`. `StubAiCaller` is programmed per test to return the `arguments` an LLM would have produced, so the test exercises resolve → (stub) extract → validate → execute.

| ID | User text | Stub LLM returns | Expected response | Verifies |
|----|-----------|------------------|-------------------|----------|
| IT-PIPE-01 | `"add 3 and 4"` | `{ADD,[3,4]}` | `{ready:true, result:7.0}` | FR-4,8,13,18,20 end-to-end |
| IT-PIPE-02 | `"what is 2 to the power 10"` | `{POWER,[2,10]}` | `{ready:true, result:1024.0}` | full success |
| IT-PIPE-03 | `"square root of 9"` | `{SQRT,[9]}` | `{ready:true, result:3.0}` | SQRT path |
| IT-PIPE-04 | `"divide 10 by 0"` | `{DIVIDE,[10,0]}` | `{ready:false, DIVIDE_BY_ZERO}`; no compute | FR-15, C3 |
| IT-PIPE-05 | `"add 3 and 4"` | stub sets `transportOk=false` | `{ready:false, LLM_PARSE_ERROR}` | FR-11 |
| IT-PIPE-06 | `"tell me a joke"` | (LLM not called) | `ErrorResponse(UNKNOWN_SIMULATION)` | FR-6 |
| IT-PIPE-07 | `"add five"` | `{ADD,[5]}` | `{ready:false, ARITY}`; no compute | FR-14, FR-15 |

```java
@Test void it_pipe_04_divide_by_zero_is_fail_closed() {
    stub.next(args("DIVIDE", 10, 0));
    Object resp = orchestrator.handle("divide 10 by 0");
    ValidationResult r = (ValidationResult) resp;
    assertThat(r.ready()).isFalse();
    assertThat(r.errors()).anyMatch(e -> e.code() == ErrorCode.DIVIDE_BY_ZERO);
}

@Test void it_pipe_06_unknown_skips_llm() {
    Object resp = orchestrator.handle("tell me a joke");
    assertThat(resp).isInstanceOf(ErrorResponse.class);
    verify(stub, never()).extractArguments(any());   // LLM not called
}
```

---

## 5. MCP Transport / Smoke Tests — SM

| ID | Action | Expected | Verifies |
|----|--------|----------|----------|
| SM-01 | Boot app with `ai.provider=stub` | context loads | wiring |
| SM-02 | `POST /mcp tools/list` | returns one tool `calculator` with schema (SRS §4.1) | FR-1, FR-3 |
| SM-03 | `POST /mcp tools/call name=calculator arguments={ADD,[3,4]}` | `{ready:true, result:7.0}` | FR-2, FR-20 |
| SM-04 | `POST /simulate {text:"add 3 and 4"}` | `{ready:true, result:7.0}` | convenience endpoint |
| SM-05 | NFR-1 smoke: pipeline (excl. LLM) under 50 ms | passes | NFR-1 |

---

## 6. Negative / Robustness Tests — NEG

| ID | Scenario | Expected | Verifies |
|----|----------|----------|----------|
| NEG-01 | LLM returns malformed JSON | `ready:false, LLM_PARSE_ERROR`; no crash | FR-11, NFR-2 |
| NEG-02 | External API throws / times out (mock) | `ErrorResponse(EXTERNAL_API_ERROR / TIMEOUT)`; no stack trace leaked | FR-21, FR-24 |
| NEG-03 | `arguments` has `additionalProperties` | rejected by schema/validation | SRS §4.1 |
| NEG-04 | Unexpected runtime exception in service | global advice → `INTERNAL_ERROR` | FR-24 |
| NEG-05 | API key absent for `openai` provider | startup/log error; key never printed | NFR-3 |

---

## 7. Requirement → Test Coverage Matrix

| Requirement | Covering tests |
|-------------|----------------|
| FR-1, FR-3 | SM-02 |
| FR-2 | SM-03 |
| FR-4, FR-5 | UT-RES-01..05, IT-PIPE-01 |
| FR-6 | UT-RES-06..08, IT-PIPE-06 |
| FR-7 | UT-RES (weighting), design review |
| FR-8, FR-9 | UT-PR-01..02, IT-PIPE-01 |
| FR-10 | UT-AI-01, IT-PIPE-* |
| FR-11 | UT-AI-03, IT-PIPE-05, NEG-01 |
| FR-12 | OpenAiCaller retry unit test (mock transport) |
| FR-13..14 | UT-VAL-04..14 |
| FR-15, C3 | UT-GATE-01, IT-PIPE-04/07 |
| FR-16 | UT-GATE-02, IT-PIPE-01..03 |
| FR-17 | UT-VAL-11/12, IT-PIPE-04 |
| FR-18..20 | UT-CALC-01..08, SM-03, IT-PIPE-01..03 |
| FR-21 | NEG-02 |
| FR-22, FR-23, NFR-5 | UT-AI-02, UT-AI-SWAP-01/02 |
| FR-24 | NEG-02/04 |
| FR-25 | logging/correlation-id test (filter) |
| NFR-1 | SM-05 |
| NFR-2 | NEG-01/02 |
| NFR-3 | NEG-05 |
| NFR-6 | entire UT suite (no network) |

---

## 8. Exit Criteria

Testing is complete when:

1. All UT, IT, SM, and NEG cases above pass.
2. Coverage ≥ 90% on `routing`, `tool.calculator`, `orchestration` packages.
3. No test performs real network I/O (LLM or external API).
4. Every requirement in §7 has at least one passing test.
5. The fail-closed property (UT-GATE-01, IT-PIPE-04/07) is demonstrably enforced: when `ready:false`, `CalculatorService.compute` is never invoked.
