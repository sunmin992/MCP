package com.wastesim.mcp;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.SimulationService;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.stereotype.Component;

/**
 * 기존 Java 재구현 엔진({@link SimulationEngine} 기반 {@link SimulationService})을
 * {@link SimulationModelProvider}로 감싼 어댑터. 리팩터링 전까지 유일한 모델이던
 * 것을 "여러 모델 중 하나"로 만든 것뿐이라, {@code modelId}/{@code toolName}은
 * 기존 MCP 도구 이름을 그대로 유지해 하위호환을 지킨다(MCP_모델_연결_방법.md §2).
 */
@Component
public class JavaEngineProvider implements SimulationModelProvider {

    private final SimulationService simulationService;

    public JavaEngineProvider(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @Override public String modelId() { return SimulationModelRegistry.DEFAULT_MODEL_ID; }
    @Override public String toolName() { return "run_waste_simulation"; }

    @Override
    public String description() {
        return "장량동 원룸촌 생활쓰레기 DEVS 시뮬레이션을 Java 재구현 엔진으로 실행하고 "
             + "월간 민원 통계를 반환한다.";
    }

    @Override
    public String inputSchemaJson() {
        return McpToolCatalog.RUN_SIM_SCHEMA;
    }

    @Override
    public ToolResult run(SimulationConfig cfg) {
        try {
            SimulationResult r = simulationService.runExperiment(cfg);
            r.setSimulationConfig(cfg);
            return ToolResult.ok(r);
        } catch (Exception e) {
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "engine", e.getMessage()));
        }
    }
}
