# Software Design Description (SDD)

**Project:** Java MCP Server with LLM-Driven Argument Extraction
**Example Domain:** Calculator Simulation
**Version:** 1.0
**Date:** 2026-06-23
**Companion documents:** SRS.md, TDD.md

---

## 1. Introduction

### 1.1 Purpose

This SDD translates the requirements in `SRS.md` into a concrete software design: package structure, classes, interfaces, data structures, control flow, and configuration. It is written so an automated coding agent can generate a working Spring Boot project from it.

### 1.2 Design Goals

1. **Separation of concerns** — the LLM extracts structure; the server owns validation and computation.
2. **Deterministic routing** — simulation type is chosen by keyword matching, never by the LLM.
3. **Provider independence** — all LLM access is behind `AiCaller`.
4. **Fail-closed** — no external call happens unless `ready == true`.
5. **Extensibility** — new simulation types plug in without touching existing ones.

---

## 2. Architecture Overview

### 2.1 Layered Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  Transport Layer                                                       │
│    McpController (/mcp: tools/list, tools/call)                        │
│    SimulationController (/simulate)            ← convenience endpoint   │
├──────────────────────────────────────────────────────────────────────┤
│  Orchestration Layer                                                   │
│    SimulationOrchestrator   (resolve → extract → validate → execute)   │
├──────────────────────────────────────────────────────────────────────┤
│  Routing Layer                                                         │
│    SimulationTypeResolver   (keyword matching, no LLM)                 │
│    PromptRegistry           (systemPrompt + JSON schema per type)      │
├──────────────────────────────────────────────────────────────────────┤
│  LLM Abstraction Layer                                                 │
│    AiCaller (interface)  ──  OpenAiCaller / StubAiCaller               │
├──────────────────────────────────────────────────────────────────────┤
│  Tool Layer (per simulation)                                          │
│    ToolController (interface)  ──  CalculatorController                │
│    Validation → ValidationResult{ready, errors}                       │
├──────────────────────────────────────────────────────────────────────┤
│  Service Layer                                                        │
│    CalculatorService  ──  CalculatorApiClient (external API boundary)  │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 Package Structure

```
com.example.mcpsim
├─ McpSimApplication.java               // Spring Boot entry point
├─ transport
│   ├─ McpController.java               // FR-1, FR-2
│   ├─ SimulationController.java        // POST /simulate
│   └─ dto
│       ├─ SimulateRequest.java
│       ├─ ToolCallRequest.java
│       ├─ ToolDescriptor.java
│       └─ ErrorResponse.java
├─ orchestration
│   └─ SimulationOrchestrator.java      // pipeline glue
├─ routing
│   ├─ SimulationType.java              // enum: CALCULATOR, UNKNOWN
│   ├─ SimulationTypeResolver.java      // FR-4..7
│   └─ PromptRegistry.java              // FR-8, FR-9
├─ ai
│   ├─ AiCaller.java                    // interface, FR-22
│   ├─ AiRequest.java                   // value object
│   ├─ AiResult.java                    // value object
│   ├─ OpenAiCaller.java                // default impl
│   └─ StubAiCaller.java                // test/offline impl
├─ tool
│   ├─ ToolController.java              // interface
│   ├─ ValidationResult.java           // {ready, errors}
│   ├─ ValidationError.java
│   └─ calculator
│       ├─ CalculatorController.java    // FR-13..17, validation
│       ├─ CalculatorArguments.java     // typed arguments DTO
│       ├─ Operation.java               // enum
│       ├─ CalculatorService.java       // FR-18..20
│       └─ CalculatorApiClient.java     // external boundary (FR-18, FR-21)
├─ config
│   ├─ AiProviderConfig.java            // selects AiCaller bean (FR-23)
│   └─ AppProperties.java               // typed config
└─ common
    ├─ ErrorCode.java
    └─ CorrelationIdFilter.java         // FR-25
```

---

## 3. Detailed Component Design

### 3.1 `SimulationType` (enum)

```java
public enum SimulationType {
    CALCULATOR,
    UNKNOWN
}
```

### 3.2 `SimulationTypeResolver` (FR-4..7)

Deterministic, LLM-free. Holds a weighted keyword map per type.

```java
@Component
public class SimulationTypeResolver {

    // keyword -> weight, grouped by type
    private static final Map<SimulationType, Map<String,Integer>> KEYWORDS = Map.of(
        SimulationType.CALCULATOR, Map.ofEntries(
            entry("add",1), entry("plus",1), entry("sum",1), entry("+",1),
            entry("subtract",1), entry("minus",1), entry("-",1),
            entry("multiply",1), entry("times",1), entry("*",1), entry("x",1),
            entry("divide",1), entry("divided",1), entry("/",1),
            entry("power",1), entry("pow",1), entry("^",1),
            entry("sqrt",1), entry("square root",2),
            entry("더하기",1), entry("빼기",1), entry("곱하기",1), entry("나누기",1)
        )
    );

    public SimulationType resolve(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        SimulationType best = SimulationType.UNKNOWN;
        int bestScore = 0;
        for (var e : KEYWORDS.entrySet()) {
            int score = 0;
            for (var kw : e.getValue().entrySet()) {
                if (t.contains(kw.getKey())) score += kw.getValue();
            }
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        return bestScore > 0 ? best : SimulationType.UNKNOWN;  // FR-6
    }
}
```

Design notes: ties resolved by enum declaration order (first wins). New types add a new map entry only (NFR-4).

### 3.3 `PromptRegistry` (FR-8, FR-9)

Maps a `SimulationType` to its `systemPrompt` and JSON Schema.

```java
@Component
public class PromptRegistry {

    public record PromptSpec(String systemPrompt, String jsonSchema) {}

    private final Map<SimulationType, PromptSpec> specs = new EnumMap<>(SimulationType.class);

    public PromptRegistry() {
        specs.put(SimulationType.CALCULATOR, new PromptSpec(
            """
            You are a structured-argument extractor for a calculator tool.
            Read the user's request and output ONLY a JSON object that conforms to the
            provided JSON schema. Do NOT compute the result. Do NOT add commentary.
            Map words to operations: plus/add->ADD, minus/subtract->SUBTRACT,
            times/multiply->MULTIPLY, divide->DIVIDE, power->POWER, square root->SQRT.
            """,
            CalculatorSchemas.INPUT_SCHEMA   // the schema string from SRS §4.1
        ));
    }

    public PromptSpec specFor(SimulationType type) {
        PromptSpec s = specs.get(type);
        if (s == null) throw new IllegalArgumentException("No prompt for " + type);
        return s;
    }
}
```

### 3.4 LLM Abstraction (`AiCaller`, FR-10, FR-22)

```java
public interface AiCaller {
    /**
     * Extract a JSON arguments object from natural language.
     * MUST NOT perform domain computation; structure extraction only.
     */
    AiResult extractArguments(AiRequest request);
}

public record AiRequest(
    String userText,
    String systemPrompt,
    String jsonSchema,
    String model,
    double temperature,
    Duration timeout
) {}

public record AiResult(
    JsonNode arguments,   // parsed JSON object, may be null on failure
    boolean transportOk,  // false => transport/parse error
    String rawText,
    String errorMessage
) {}
```

**`OpenAiCaller`** — default implementation. Uses JSON mode / function-calling so the model returns a JSON object; parses to `JsonNode`. On HTTP/parse error returns `AiResult` with `transportOk=false`. Retries up to `N` (FR-12).

**`StubAiCaller`** — offline implementation used in tests and `ai.provider=stub`. Returns a fixed/parameterized `JsonNode`. Demonstrates that swapping providers is config-only (NFR-5).

`AiProviderConfig` selects the bean:

```java
@Configuration
public class AiProviderConfig {
    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "openai", matchIfMissing = true)
    AiCaller openAiCaller(AppProperties props, WebClient.Builder b) { return new OpenAiCaller(props, b); }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "stub")
    AiCaller stubAiCaller() { return new StubAiCaller(); }
}
```

### 3.5 Tool Layer

#### 3.5.1 `ToolController` (interface)

```java
public interface ToolController {
    String toolName();                                   // e.g. "calculator"
    ValidationResult validate(JsonNode arguments);       // FR-13..17 (fail-closed)
    Object execute(JsonNode arguments);                  // called only when ready
}
```

#### 3.5.2 `ValidationResult` & `ValidationError`

```java
public record ValidationResult(boolean ready, List<ValidationError> errors) {
    public static ValidationResult ok()                 { return new ValidationResult(true, List.of()); }
    public static ValidationResult fail(List<ValidationError> e) { return new ValidationResult(false, e); }
    public static ValidationResult fail(ValidationError e)       { return fail(List.of(e)); }
}

public record ValidationError(ErrorCode code, String field, String message) {}
```

#### 3.5.3 `Operation` & `CalculatorArguments`

```java
public enum Operation { ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER, SQRT }

public record CalculatorArguments(Operation operation, List<Double> operands) {}
```

#### 3.5.4 `CalculatorController` (FR-13..17)

Implements all server-side validation rules from SRS §4.2 **before** any computation.

```java
@Component
public class CalculatorController implements ToolController {

    private final CalculatorService service;
    private static final double LIMIT = 1e12;

    @Override public String toolName() { return "calculator"; }

    @Override
    public ValidationResult validate(JsonNode args) {
        List<ValidationError> errs = new ArrayList<>();

        // V-1 operation present & valid
        JsonNode opNode = args.get("operation");
        Operation op = null;
        if (opNode == null || opNode.asText().isBlank()) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "operation", "operation is required"));
        } else {
            try { op = Operation.valueOf(opNode.asText().trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "operation",
                        "Unsupported operation: " + opNode.asText()));
            }
        }

        // V-2 operands present & non-empty
        JsonNode ops = args.get("operands");
        List<Double> values = new ArrayList<>();
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            errs.add(new ValidationError(ErrorCode.EMPTY_FIELD, "operands", "operands must be a non-empty array"));
        } else {
            for (JsonNode n : ops) {
                if (!n.isNumber()) {
                    errs.add(new ValidationError(ErrorCode.WRONG_TYPE, "operands", "operands must be numbers"));
                } else {
                    double v = n.asDouble();
                    if (!Double.isFinite(v) || Math.abs(v) > LIMIT)   // V-6
                        errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "operands",
                                "operand out of allowed range [-1e12, 1e12]"));
                    values.add(v);
                }
            }
        }

        // arity & domain rules (only if op resolved and operands numeric)
        if (op != null && errs.isEmpty()) {
            if (op == Operation.SQRT) {
                if (values.size() != 1)                                  // V-3
                    errs.add(new ValidationError(ErrorCode.ARITY, "operands", "SQRT requires exactly one operand"));
                else if (values.get(0) < 0)
                    errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "operands", "SQRT operand must be >= 0"));
            } else {
                if (values.size() < 2)                                   // V-4
                    errs.add(new ValidationError(ErrorCode.ARITY, "operands", op + " requires at least two operands"));
                if (op == Operation.DIVIDE) {                            // V-5
                    for (int i = 1; i < values.size(); i++)
                        if (values.get(i) == 0.0)
                            errs.add(new ValidationError(ErrorCode.DIVIDE_BY_ZERO, "operands", "Divisor must not be zero"));
                }
            }
        }

        return errs.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errs);
    }

    @Override
    public Object execute(JsonNode args) {
        Operation op = Operation.valueOf(args.get("operation").asText().toUpperCase(Locale.ROOT));
        List<Double> values = new ArrayList<>();
        args.get("operands").forEach(n -> values.add(n.asDouble()));
        double result = service.compute(op, values);   // external API boundary
        return Map.of("ready", true, "operation", op.name(), "operands", values, "result", result);
    }
}
```

#### 3.5.5 `CalculatorService` & `CalculatorApiClient` (FR-18..21)

`CalculatorService` orchestrates the external call; `CalculatorApiClient` is the boundary that, in the reference build, **simulates** an external API. A real HTTP client can replace it without changing callers (NFR-5).

```java
@Service
public class CalculatorService {
    private final CalculatorApiClient client;
    public double compute(Operation op, List<Double> operands) {
        return client.calculate(op, operands);   // could be remote HTTP in production
    }
}

@Component
public class CalculatorApiClient {              // simulated external API
    public double calculate(Operation op, List<Double> v) {
        return switch (op) {
            case ADD      -> v.stream().mapToDouble(Double::doubleValue).sum();
            case SUBTRACT -> fold(v, (a, b) -> a - b);
            case MULTIPLY -> v.stream().reduce(1.0, (a, b) -> a * b);
            case DIVIDE   -> fold(v, (a, b) -> a / b);   // divisors already validated != 0
            case POWER    -> fold(v, Math::pow);
            case SQRT     -> Math.sqrt(v.get(0));        // operand already validated >= 0
        };
    }
    private double fold(List<Double> v, DoubleBinaryOperator f) {
        double acc = v.get(0);
        for (int i = 1; i < v.size(); i++) acc = f.applyAsDouble(acc, v.get(i));
        return acc;
    }
}
```

### 3.6 `SimulationOrchestrator` (pipeline)

Glues the four stages and enforces the readiness gate (FR-15, C3).

```java
@Service
public class SimulationOrchestrator {

    private final SimulationTypeResolver resolver;
    private final PromptRegistry prompts;
    private final AiCaller aiCaller;
    private final Map<String, ToolController> controllers; // keyed by tool name
    private final AppProperties props;

    public Object handle(String userText) {
        // Stage 1: resolve (no LLM)
        SimulationType type = resolver.resolve(userText);
        if (type == SimulationType.UNKNOWN)
            return ErrorResponse.of(ErrorCode.UNKNOWN_SIMULATION,
                    "Could not determine a simulation type from the request.");

        // Stage 2: extract via LLM (structure only)
        var spec = prompts.specFor(type);
        AiResult ai = aiCaller.extractArguments(new AiRequest(
                userText, spec.systemPrompt(), spec.jsonSchema(),
                props.model(), 0.0, props.aiTimeout()));
        if (!ai.transportOk() || ai.arguments() == null)
            return new ValidationResult(false, List.of(
                new ValidationError(ErrorCode.LLM_PARSE_ERROR, "arguments",
                    "Failed to extract arguments: " + ai.errorMessage())));

        // Stage 3: validate (fail-closed gate)
        ToolController controller = controllers.get(toolNameFor(type));
        ValidationResult vr = controller.validate(ai.arguments());
        if (!vr.ready()) return vr;        // FR-15: external API NOT called

        // Stage 4: execute external API
        return controller.execute(ai.arguments());   // FR-18..20
    }

    private String toolNameFor(SimulationType t) {
        return t == SimulationType.CALCULATOR ? "calculator" : "";
    }
}
```

---

## 4. Control Flow

### 4.1 End-to-End Sequence (success)

```
Client      McpController   Orchestrator   Resolver   PromptRegistry   AiCaller   CalculatorController   CalculatorService
  │  text       │               │             │            │             │               │                    │
  │────────────▶│  handle(text) │             │            │             │               │                    │
  │             │──────────────▶│ resolve     │            │             │               │                    │
  │             │               │────────────▶│ CALCULATOR │             │               │                    │
  │             │               │ specFor(CALCULATOR)──────▶│ PromptSpec  │               │                    │
  │             │               │ extractArguments(...) ───────────────▶ │ arguments JSON │                    │
  │             │               │ validate(arguments) ───────────────────────────────────▶│ ready:true        │
  │             │               │ execute(arguments) ────────────────────────────────────▶│ compute ─────────▶│ result
  │◀────────────│◀──────────────│  { ready:true, result } ◀──────────────────────────────────────────────────│
```

### 4.2 Validation-Failure Path (fail-closed)

```
... validate(arguments) → ValidationResult{ready:false, errors:[...]}
Orchestrator returns immediately. CalculatorService / external API is NEVER invoked. (C3, FR-15)
```

---

## 5. Data Structures (DTOs)

| Type | Fields |
|------|--------|
| `SimulateRequest` | `String text` |
| `ToolCallRequest` | `String name`, `JsonNode arguments` |
| `ToolDescriptor` | `String name`, `String description`, `JsonNode inputSchema` |
| `AiRequest` | `userText`, `systemPrompt`, `jsonSchema`, `model`, `temperature`, `timeout` |
| `AiResult` | `arguments`, `transportOk`, `rawText`, `errorMessage` |
| `ValidationResult` | `boolean ready`, `List<ValidationError> errors` |
| `ValidationError` | `ErrorCode code`, `String field`, `String message` |
| `ErrorResponse` | `ErrorCode code`, `String message` |

### 5.1 `ErrorCode` (enum)

```java
public enum ErrorCode {
    UNKNOWN_SIMULATION, LLM_PARSE_ERROR, MISSING_FIELD, EMPTY_FIELD,
    WRONG_TYPE, INVALID_ENUM, OUT_OF_RANGE, ARITY, DIVIDE_BY_ZERO,
    EXTERNAL_API_ERROR, TIMEOUT, INTERNAL_ERROR
}
```

---

## 6. Configuration

`application.yml`:

```yaml
ai:
  provider: openai          # openai | stub  (FR-23, NFR-5)
  model: gpt-4o-mini
  api-key: ${OPENAI_API_KEY}
  timeout-ms: 8000
  retries: 1

external:
  calculator:
    timeout-ms: 2000        # FR-21
```

`AppProperties` binds these with `@ConfigurationProperties(prefix = "...")`. API keys come from environment variables only (NFR-3).

---

## 7. Error Handling Strategy

| Stage | Failure | Handling |
|-------|---------|----------|
| Resolve | no keyword match | `ErrorResponse(UNKNOWN_SIMULATION)`; stop. |
| Extract | transport/parse error | `ValidationResult(ready:false, LLM_PARSE_ERROR)`; stop. |
| Validate | any rule fails | `ValidationResult(ready:false, [...])`; external API not called. |
| Execute | external timeout/error | `ErrorResponse(TIMEOUT / EXTERNAL_API_ERROR)`; controlled message. |
| Any | unexpected exception | global `@ControllerAdvice` → `ErrorResponse(INTERNAL_ERROR)`; nothing leaks. |

---

## 8. Extensibility Guide (adding a new simulation type)

To add, e.g., a `PHYSICS` simulation:

1. Add `PHYSICS` to `SimulationType`.
2. Add its keyword map to `SimulationTypeResolver`.
3. Register its `systemPrompt` + JSON schema in `PromptRegistry`.
4. Implement a `PhysicsController implements ToolController` with its own `validate`/`execute`.
5. Add a `PhysicsService` + external boundary.

No existing type, the `AiCaller`, or the orchestrator changes (NFR-4).

---

## 9. Design-to-Requirement Traceability

| SDD element | Satisfies |
|-------------|-----------|
| `SimulationTypeResolver` | FR-4..7, NFR-4 |
| `PromptRegistry` | FR-8, FR-9 |
| `AiCaller` / `OpenAiCaller` / `StubAiCaller` / `AiProviderConfig` | FR-10, FR-12, FR-22, FR-23, NFR-5 |
| `CalculatorController.validate()` | FR-13..17, SRS §4.2 |
| `SimulationOrchestrator` (gate) | FR-15, C3 |
| `CalculatorService` / `CalculatorApiClient` | FR-18..21 |
| `ErrorCode`, `@ControllerAdvice`, `CorrelationIdFilter` | FR-24, FR-25 |
