# Software Requirements Specification (SRS)

**Project:** Java MCP Server with LLM-Driven Argument Extraction
**Example Domain:** Calculator Simulation
**Version:** 1.0
**Date:** 2026-06-23
**Status:** Baseline

---

## 1. Introduction

### 1.1 Purpose

This document specifies the requirements for a **Model Context Protocol (MCP) server implemented in Java**. The server accepts natural-language requests, uses a Large Language Model (LLM, e.g. GPT) **only to extract a structured `arguments` JSON object** from free text, validates those arguments on the server side, and—if valid—invokes an external simulation API to perform the actual computation.

The guiding architectural principle is a strict **separation of responsibility**:

- The **LLM extracts structure only** (it maps natural language to a JSON object that conforms to a tool's JSON Schema).
- The **server owns all logic**: parameter validation, range checking, and computation.

A **calculator simulation** is included as the single concrete reference implementation so that an automated coding agent can generate working code from this specification.

### 1.2 Scope

The system SHALL:

1. Expose tools over the MCP protocol.
2. Resolve which **simulation type** a request targets using **deterministic keyword matching (no LLM)**.
3. Inject a per-simulation `systemPrompt` into the LLM call to steer extraction.
4. Use a pluggable `AiCaller` abstraction so the underlying LLM provider can be swapped without changing business code.
5. Validate every extracted argument set before any external call.
6. Return `{ "ready": false, ... }` and **block the external API call** whenever validation fails.
7. Execute the external simulation API only when `ready == true`.

Out of scope: front-end UI, authentication/authorization beyond a placeholder, persistence of conversation history, and real (non-simulated) external services other than the calculator example.

### 1.3 Definitions, Acronyms, Abbreviations

| Term | Meaning |
|------|---------|
| MCP | Model Context Protocol — a standard for exposing tools/resources to LLM clients. |
| Tool | A named, schema-described capability the server exposes (e.g. `calculator`). |
| `arguments` | The JSON object of parameters for a tool call, produced by the LLM. |
| Simulation type | A category of request (e.g. `CALCULATOR`) resolved by keyword matching. |
| `systemPrompt` | The system message handed to the LLM to constrain its behavior for a simulation type. |
| `AiCaller` | The interface that abstracts an LLM provider. |
| `ready` | Boolean gate; `true` only after server-side validation passes. |
| Validation gate | The server-side check that must pass before the external API is called. |

### 1.4 References

- Model Context Protocol specification (JSON-RPC 2.0 based).
- JSON Schema Draft 2020-12.
- OpenAI Chat Completions API (reference LLM provider).

---

## 2. Overall Description

### 2.1 Product Perspective

The server is a stateless backend component that sits between an MCP client (the natural-language source) and one or more external simulation APIs.

```
                 +-----------------------------------------------------+
  natural        |                  Java MCP Server                     |
  language        |                                                     |
  ───────────────▶|  1. SimulationTypeResolver  (keyword match, no LLM) |
                 |  2. AiCaller  ──▶  LLM (GPT)  → arguments JSON       |
                 |  3. ToolController.validate() → {ready:true/false}   |
                 |  4. if ready: SimulationService → External API       |
                 |                                                     |
                 +-----------------------------------------------------+
                                              │ (only if ready==true)
                                              ▼
                                   External Simulation API
                                   (Calculator example)
```

### 2.2 Product Functions (Summary)

- F1: Discover/list available MCP tools.
- F2: Resolve simulation type by keyword.
- F3: Extract `arguments` JSON via the LLM under a steering `systemPrompt`.
- F4: Validate arguments and gate execution with `ready`.
- F5: Invoke the external simulation API and return its result.
- F6: Swap LLM providers without code changes to controllers/services.

### 2.3 User Classes

| User class | Description |
|------------|-------------|
| MCP client / end user | Sends natural-language requests; expects a computed result or a clear validation error. |
| Integrator / developer | Adds new simulation types and swaps LLM providers. |
| Operator | Configures API keys, endpoints, and timeouts. |

### 2.4 Operating Environment

- Java 21 (LTS).
- Spring Boot 3.x.
- Build: Gradle (Kotlin DSL) or Maven.
- JSON: Jackson.
- HTTP client for LLM and external API: Spring `WebClient` / `RestClient`.
- Runs as a standalone service (HTTP/JSON-RPC transport for MCP).

### 2.5 Design & Implementation Constraints

- C1: The LLM MUST NOT perform arithmetic or final decision logic; it only emits `arguments` conforming to the tool schema.
- C2: Simulation-type resolution MUST be deterministic and LLM-free.
- C3: No external API call may occur while `ready == false`.
- C4: All LLM access MUST go through the `AiCaller` interface.
- C5: Validation logic MUST live in the server, not in prompts.

### 2.6 Assumptions & Dependencies

- The LLM provider is reachable and returns JSON (using JSON mode / function-calling where available).
- The external calculator API (or its in-process simulation) is available.
- Network failures and timeouts are possible and must be handled gracefully.

---

## 3. Functional Requirements

Each requirement is uniquely identified (FR-x), assigned a priority (M = Must, S = Should, C = Could), and written to be testable.

### 3.1 MCP Tool Exposure

- **FR-1 (M):** The server SHALL expose an MCP endpoint that lists available tools, each with `name`, `description`, and a JSON Schema (`inputSchema`).
- **FR-2 (M):** The server SHALL accept an MCP `tools/call` request containing a `name` and an `arguments` object.
- **FR-3 (M):** For the reference build, the server SHALL expose exactly one tool named `calculator`.

### 3.2 Simulation Type Resolution (Keyword Matching)

- **FR-4 (M):** Given the raw user text, the server SHALL determine a `SimulationType` via case-insensitive keyword matching, **without invoking the LLM**.
- **FR-5 (M):** The resolver SHALL map keywords to `CALCULATOR` (e.g. `add`, `plus`, `sum`, `subtract`, `minus`, `multiply`, `times`, `divide`, `power`, `square root`, `sqrt`, `+`, `-`, `*`, `/`, and Korean equivalents such as `더하기`, `빼기`, `곱하기`, `나누기`).
- **FR-6 (M):** If no keyword matches, the resolver SHALL return `UNKNOWN`, and the server SHALL respond with a guidance message and SHALL NOT call the LLM or external API.
- **FR-7 (S):** When multiple types match, the resolver SHALL select the type with the highest weighted match count; ties SHALL be broken by a fixed priority order.

### 3.3 LLM Argument Extraction

- **FR-8 (M):** For a resolved simulation type, the server SHALL set the type-specific `systemPrompt` as the LLM **system** message.
- **FR-9 (M):** The server SHALL pass the tool's JSON Schema to the LLM so the returned `arguments` conform to that schema.
- **FR-10 (M):** The LLM call SHALL be made exclusively through `AiCaller.extractArguments(...)`.
- **FR-11 (M):** The server SHALL parse the LLM response into a JSON object; on parse failure it SHALL treat the result as a validation failure (`ready:false`) with an explanatory error.
- **FR-12 (S):** The server SHALL retry the LLM call at most `N` (configurable, default 1) times on transient/transport errors.

### 3.4 Parameter Validation & Readiness Gate

- **FR-13 (M):** Before any external call, the relevant `ToolController` SHALL validate the `arguments` against schema and domain rules.
- **FR-14 (M):** Validation SHALL fail (and produce `ready:false`) for: missing required fields, empty fields, wrong types, out-of-range values, and disallowed enum values.
- **FR-15 (M):** On validation failure, the server SHALL return `{ "ready": false, "errors": [ ... ] }` and SHALL NOT invoke the external API.
- **FR-16 (M):** On validation success, the server SHALL set `ready:true` and proceed to the external call.
- **FR-17 (M):** Division by zero in the calculator SHALL be a validation failure (`ready:false`), not a runtime exception.

### 3.5 External Simulation API Invocation (Calculator)

- **FR-18 (M):** When `ready:true`, the server SHALL invoke the calculator simulation with the validated `operation` and `operands`.
- **FR-19 (M):** The calculator SHALL support: `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `POWER`, `SQRT`.
- **FR-20 (M):** The server SHALL return a structured result: `{ "ready": true, "operation": ..., "operands": [...], "result": <number> }`.
- **FR-21 (S):** The external call SHALL have a configurable timeout; on timeout the server SHALL return a controlled error (not a stack trace).

### 3.6 LLM Provider Pluggability

- **FR-22 (M):** All LLM access SHALL be behind the `AiCaller` interface; swapping providers SHALL require only adding a new implementation and changing configuration—no changes to controllers or services.
- **FR-23 (S):** Provider selection SHALL be configurable (e.g. property `ai.provider=openai`).

### 3.7 Error Handling & Observability

- **FR-24 (M):** All externally visible errors SHALL be returned as structured JSON with a stable `errorCode` and human-readable `message`.
- **FR-25 (S):** The server SHALL log each stage (resolve → extract → validate → execute) with a correlation id.

---

## 4. Tool Contract: `calculator`

### 4.1 JSON Schema (`inputSchema`)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "title": "calculator arguments",
  "properties": {
    "operation": {
      "type": "string",
      "enum": ["ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "POWER", "SQRT"],
      "description": "The arithmetic operation to perform."
    },
    "operands": {
      "type": "array",
      "items": { "type": "number" },
      "minItems": 1,
      "maxItems": 10,
      "description": "Operands in order. SQRT uses exactly one; others use two or more."
    }
  },
  "required": ["operation", "operands"],
  "additionalProperties": false
}
```

### 4.2 Domain Validation Rules (server-side, beyond schema)

| Rule ID | Rule |
|---------|------|
| V-1 | `operation` MUST be one of the enum values (case-normalized to upper-case). |
| V-2 | `operands` MUST be non-empty. |
| V-3 | `SQRT` MUST have exactly **one** operand; that operand MUST be `>= 0`. |
| V-4 | `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `POWER` MUST have **at least two** operands. |
| V-5 | `DIVIDE` MUST NOT contain a zero in any divisor position (operands after the first). |
| V-6 | Each operand MUST be a finite number within `[-1e12, 1e12]`. |

### 4.3 Example Interactions

**Success**

```
User: "What is 3 plus 4 times 2?"  →  resolved type CALCULATOR
LLM  → { "operation": "ADD", "operands": [3, 8] }   (model's structural extraction)
Validate → ready:true
Result → { "ready": true, "operation": "ADD", "operands": [3, 8], "result": 11 }
```

**Validation failure (division by zero)**

```
User: "divide 10 by 0"
LLM  → { "operation": "DIVIDE", "operands": [10, 0] }
Validate → V-5 fails
Result → { "ready": false, "errors": [
            { "code": "DIVIDE_BY_ZERO", "field": "operands", "message": "Divisor must not be zero." } ] }
External API is NOT called.
```

---

## 5. External Interface Requirements

### 5.1 MCP Interface

- Transport: JSON-RPC 2.0 over HTTP (`POST /mcp`).
- Methods: `tools/list`, `tools/call`.
- A convenience endpoint `POST /simulate` MAY accept `{ "text": "<natural language>" }` to exercise the full pipeline end to end.

### 5.2 LLM Provider Interface (`AiCaller`)

- Input: user text, JSON Schema, `systemPrompt`, options (model, temperature, timeout).
- Output: a JSON object (the `arguments`) or a transport error.

### 5.3 External Calculator API

- For the reference build the calculator MAY be an in-process service that **simulates** an external API; the call site MUST be behind an interface so a real HTTP API can replace it without changing callers.

---

## 6. Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-1 | Performance | A full pipeline call (excluding LLM latency) SHALL complete in < 50 ms for the calculator example. |
| NFR-2 | Reliability | LLM/external transport failures SHALL never crash the server; they SHALL surface as structured errors. |
| NFR-3 | Security | API keys SHALL be read from environment/config, never hard-coded or logged. |
| NFR-4 | Maintainability | Adding a new simulation type SHALL require a new resolver entry, a `systemPrompt`, a schema, and a `ToolController`—no changes to existing types. |
| NFR-5 | Portability | Swapping LLM providers SHALL require no changes outside an `AiCaller` implementation and configuration. |
| NFR-6 | Testability | Validation and resolution SHALL be unit-testable without any network access (LLM and external API mockable). |
| NFR-7 | Observability | Each request SHALL be traceable through all four stages via a correlation id. |

---

## 7. Traceability Matrix (Requirement → Design → Test)

| Requirement | Design element (SDD) | Test (TDD) |
|-------------|----------------------|------------|
| FR-4..7 | `SimulationTypeResolver` | UT-RES-* |
| FR-8..12 | `AiCaller`, `OpenAiCaller`, `PromptRegistry` | UT-AI-*, IT-PIPE-* |
| FR-13..17 | `CalculatorController.validate()`, `ValidationResult` | UT-VAL-* |
| FR-18..21 | `CalculatorService`, `CalculatorApiClient` | UT-CALC-*, IT-PIPE-* |
| FR-22..23 | `AiCaller` interface + config | UT-AI-SWAP-* |
| FR-24..25 | `ErrorResponse`, logging filter | UT-ERR-*, IT-ERR-* |

---

## 8. Acceptance Criteria

The reference build is accepted when:

1. `tools/list` returns the `calculator` tool with the schema in §4.1.
2. A natural-language calculation request returns a correct `result` with `ready:true`.
3. Every rule in §4.2 produces `ready:false` with a matching error code and **no** external call.
4. Replacing `OpenAiCaller` with a stub `AiCaller` requires only a configuration change.
5. All TDD test cases pass.
