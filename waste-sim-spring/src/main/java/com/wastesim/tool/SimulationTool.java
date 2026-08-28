package com.wastesim.tool;

import com.wastesim.mcp.SimulationModelProvider;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 툴 파사드 — 이 시스템의 척추.
 * "검증(validate) → 실행(execute)"를 캡슐화하며, 세 진입점(MCP tools/call,
 * REST 컨트롤러, 자연어 채팅)이 모두 이 한 곳을 호출한다. 검증 실패 시
 * 실행하지 않고 사유를 반환한다(fail-closed).
 *
 * <p>실행(execute) 단계는 {@link SimulationModelRegistry}에 등록된
 * {@link SimulationModelProvider}에 위임한다(MCP_모델_연결_방법.md §3.4) —
 * 검증 규칙·메트릭·오류 처리는 어떤 모델(Java/Python 등)을 고르든 이 한 곳을
 * 공유한다. 모델을 지정하지 않는 기존 호출부는 전부 기본 모델
 * ({@link SimulationModelRegistry#DEFAULT_MODEL_ID}, 기존 Java 엔진)로
 * 동작해 하위호환을 유지한다.
 */
@Component
public class SimulationTool {

    private final SimulationConfigValidator validator;
    private final SimulationModelRegistry models;
    private final ScenarioService scenarioService;
    private final MeterRegistry metrics;

    public SimulationTool(SimulationConfigValidator validator,
                          SimulationModelRegistry models,
                          ScenarioService scenarioService,
                          MeterRegistry metrics) {
        this.validator = validator;
        this.models = models;
        this.scenarioService = scenarioService;
        this.metrics = metrics;
    }

    /** 검증만 수행(실행 없음) — 단일 시드 실행 등에서 재사용. */
    public ValidationResult validate(SimulationConfig cfg) {
        return validator.validate(cfg);
    }

    /**
     * 단일 정책 시뮬레이션(다중 시드) 실행 — REST/MCP 기본 경로. 비차단
     * 경고(V-T5)가 있어도 확인을 기다릴 상대(사람)가 없으므로 곧바로 실행한다.
     */
    public ToolResult runSimulation(SimulationConfig cfg) {
        return runSimulation(cfg, true);
    }

    /**
     * @param skipWarnings false면 비차단 경고(V-T5 피크 시각 등)가 있을 때
     *   실행하지 않고 {@link ToolResult#needsConfirm}을 반환한다(사람이 확인
     *   버튼을 눌러 재호출할 때 true로 넘겨 강행). 채팅 경로 전용
     *   (TRAFFIC_EXTENSION_DESIGN.md §7.2).
     */
    public ToolResult runSimulation(SimulationConfig cfg, boolean skipWarnings) {
        return runSimulation(cfg, SimulationModelRegistry.DEFAULT_MODEL_ID, skipWarnings);
    }

    /**
     * 모델을 지정해 실행한다(MCP_모델_연결_방법.md §3.4) — 검증·경고 처리는
     * {@code modelId}와 무관하게 항상 동일하고, 실제 계산만
     * {@link SimulationModelProvider#run}에 위임한다.
     */
    public ToolResult runSimulation(SimulationConfig cfg, String modelId, boolean skipWarnings) {
        ValidationResult vr = validator.validate(cfg);
        if (!vr.ready()) {
            metrics.counter("waste.sim.rejected").increment();
            return ToolResult.rejected(vr.errors());
        }
        if (!skipWarnings && !vr.warnings().isEmpty()) {
            metrics.counter("waste.sim.needs_confirm").increment();
            return ToolResult.needsConfirm(cfg, vr.warnings());
        }
        SimulationModelProvider model = models.byId(modelId);
        if (model == null) {
            return ToolResult.rejected(new ValidationError(
                    ErrorCode.INVALID_ENUM, "modelId", "알 수 없는 시뮬레이션 모델: " + modelId));
        }
        try {
            ToolResult r = metrics.timer("waste.sim.duration").record(() -> model.run(cfg));
            metrics.counter("waste.sim.run", "model", model.modelId()).increment();
            if (!r.ready()) metrics.counter("waste.sim.error", "model", model.modelId()).increment();
            return r;
        } catch (Exception e) {
            metrics.counter("waste.sim.error", "model", model.modelId()).increment();
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "engine", e.getMessage()));
        }
    }

    /**
     * 경로 순서만 갈아끼워 재실행(동적 라우팅, TRAFFIC_EXTENSION_DESIGN.md §6.2
     * 시나리오 2). V-T4(노드 유효성)는 {@link #runSimulation}이 내부적으로
     * 호출하는 검증기를 통해 자동 적용된다.
     */
    public ToolResult updateRouteSequence(SimulationConfig base, List<String> route) {
        SimulationConfig cfg = base.copy();
        cfg.setRouteSequence(route);
        return runSimulation(cfg, true);   // MCP 전용 경로 — 확인 절차 없이 바로 재실행
    }

    /** 복잡한 시나리오 실험 — 유형별로 sweep/그리드를 구동한다. */
    public ToolResult runScenario(String type, SimulationConfig base) {
        ValidationResult vr = validator.validate(base);
        if (!vr.ready()) {
            metrics.counter("waste.scenario.rejected").increment();
            return ToolResult.rejected(vr.errors());
        }
        try {
            ScenarioResponse resp = switch (type == null ? "" : type) {
                case "occupation-mix"      -> scenarioService.occupationMixComparison(base, null);
                case "collection-sweep"    -> scenarioService.collectionSweep(base, 6 * 60, 18 * 60, 60);
                case "behavior-grid"       -> scenarioService.behaviorGrid(base, null, null);
                case "infra-grid"          -> scenarioService.infraGrid(base, null, null);
                case "density"             -> scenarioService.densityComparison(base, null);
                case "collection-schedule" -> scenarioService.collectionSchedule(base);
                case "multi-truck"         -> scenarioService.multiTruck(base, null);
                case "waste-separation"    -> scenarioService.wasteSeparation(base);
                case "new-occupations"     -> scenarioService.newOccupations(base, null);
                case "coupling-variants"   -> scenarioService.couplingVariants(base);
                case "monthly-waste"       -> scenarioService.monthlyWaste(base, null);
                case "truck-route"         -> scenarioService.truckRouteSearch(base, null, null);
                default -> null;
            };
            if (resp == null) {
                return ToolResult.rejected(new ValidationError(
                        ErrorCode.INVALID_ENUM, "type", "알 수 없는 시나리오 유형: " + type));
            }
            metrics.counter("waste.scenario.run").increment();
            return ToolResult.ok(resp);
        } catch (Exception e) {
            metrics.counter("waste.scenario.error").increment();
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "scenario", e.getMessage()));
        }
    }

    /**
     * REST 시나리오 엔드포인트처럼 유형별 추가 축 파라미터(times/alphas/betas/
     * truckCounts 등)가 {@link #runScenario} 표준 시그니처를 벗어날 때 쓰는
     * 범용 경로. 검증·메트릭·오류 처리는 동일하게 이 파사드가 담당하고,
     * 실제 계산 로직만 호출자가 supplier로 제공한다.
     */
    public ToolResult runScenarioCustom(SimulationConfig base, Supplier<ScenarioResponse> executor) {
        ValidationResult vr = validator.validate(base);
        if (!vr.ready()) {
            metrics.counter("waste.scenario.rejected").increment();
            return ToolResult.rejected(vr.errors());
        }
        try {
            ScenarioResponse resp = executor.get();
            metrics.counter("waste.scenario.run").increment();
            return ToolResult.ok(resp);
        } catch (Exception e) {
            metrics.counter("waste.scenario.error").increment();
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "scenario", e.getMessage()));
        }
    }

    /** 채팅에 명시된 복수 수거 시각만 비교하는 검증 포함 진입점. */
    public ToolResult compareCollectionTimes(SimulationConfig base, List<Integer> times) {
        return runScenarioCustom(base, () -> scenarioService.collectionTimeComparison(base, times));
    }

    /**
     * 수거 시각 스윕의 검증 포함 진입점.
     *
     * <p>스윕 인자(범위·간격)는 {@code SimulationConfig}가 아니라 별도 파라미터라
     * {@link #runScenarioCustom}의 설정 검증 게이트가 보지 못한다. 그대로 두면 잘못된
     * 간격이 루프까지 도달하는데, 그 루프는 간격이 0 이하일 때 끝나지 않는다.
     *
     * <p>{@link ScenarioService#collectionSweep}도 같은 불변식을 스스로 지키지만
     * 거기서 던지는 예외는 {@code runScenarioCustom}이 {@code EXECUTION_ERROR}로
     * 감싼다 — 사용자 입력 오류가 실행 장애처럼 보인다. 그래서 어느 필드가 왜 틀렸는지
     * 가리키는 코드를 여기서 먼저 붙인다(D-42와 같은 이유: 검증은 게이트 안에서, 그러나
     * 코드는 원인에 맞게).
     */
    public ToolResult runCollectionSweep(SimulationConfig base, int startMin, int endMin, int stepMin) {
        if (stepMin <= 0) {
            return ToolResult.rejected(new ValidationError(ErrorCode.OUT_OF_RANGE, "stepMinutes",
                    "스윕 간격은 1분 이상이어야 합니다 (받은 값: " + stepMin + ")."));
        }
        if (startMin > endMin) {
            return ToolResult.rejected(new ValidationError(ErrorCode.OUT_OF_RANGE, "start",
                    "스윕 시작 시각이 종료 시각보다 늦습니다 (시작 "
                            + SimulationConfig.minutesToHhmm(startMin)
                            + ", 종료 " + SimulationConfig.minutesToHhmm(endMin) + ")."));
        }
        long points = (long) (endMin - startMin) / stepMin + 1;
        if (points > ScenarioService.MAX_SWEEP_POINTS) {
            return ToolResult.rejected(new ValidationError(ErrorCode.OUT_OF_RANGE, "stepMinutes",
                    "스윕 후보가 " + points + "개로 상한 " + ScenarioService.MAX_SWEEP_POINTS
                            + "개를 넘습니다. 간격을 넓히거나 범위를 좁혀 주세요."));
        }
        return runScenarioCustom(base, () -> scenarioService.collectionSweep(base, startMin, endMin, stepMin));
    }

    /** 지원 시나리오 유형 목록(디스커버리용). */
    public List<String> scenarioTypes() {
        return List.of("occupation-mix", "collection-sweep", "behavior-grid", "infra-grid",
                "density", "collection-schedule", "multi-truck", "waste-separation",
                "new-occupations", "coupling-variants", "monthly-waste", "truck-route");
    }
}
