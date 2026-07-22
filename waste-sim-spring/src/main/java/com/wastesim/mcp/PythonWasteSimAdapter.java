package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 원본 논문 재현 Python/pyevsim DEVS 엔진({@code waste_sim}, adev-master)을
 * 서브프로세스로 호출하는 어댑터(MCP_모델_연결_방법.md §3.2). Java 엔진과
 * 결과를 비교할 참조 구현으로 {@code run_waste_simulation_devs} 도구로 노출된다.
 *
 * <p>{@code waste_sim/mcp_bridge.py}가 stdin으로 JSON 설정(McpToolCatalog의
 * RUN_SIM_SCHEMA와 동일 필드명)을 받아, seeds 횟수만큼 반복 실행한 뒤 평균·
 * 표준편차 등을 집계한 JSON 한 줄을 stdout에 낸다. 이 어댑터는 그 결과를
 * 그대로(가공 없이) {@link ToolResult#ok}로 감싸 반환한다 — Python 쪽 필드명을
 * Java {@code SimulationResult}와 억지로 맞추지 않고 그대로 노출해, MCP
 * 클라이언트가 "어느 엔진 결과인지" 그대로 구분할 수 있게 한다.
 */
@Component
public class PythonWasteSimAdapter implements SimulationModelProvider {

    private static final Logger log = LoggerFactory.getLogger(PythonWasteSimAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${waste-sim.python.executable}")
    private String pythonExecutable;

    @Value("${waste-sim.python.project-root}")
    private String pythonProjectRoot;

    @Value("${waste-sim.python.timeout-seconds:30}")
    private long timeoutSeconds;

    @Override public String modelId() { return "python-devs"; }
    @Override public String toolName() { return "run_waste_simulation_devs"; }

    @Override
    public String description() {
        return "원본 논문 재현 Python/pyevsim DEVS 엔진으로 시뮬레이션을 실행한다 "
             + "(Java 엔진과 결과 비교용 참조 구현).";
    }

    @Override
    public String inputSchemaJson() {
        return McpToolCatalog.RUN_SIM_SCHEMA;
    }

    @Override
    public ToolResult run(SimulationConfig cfg) {
        String requestJson;
        try {
            requestJson = toBridgeJson(cfg);
        } catch (Exception e) {
            return ToolResult.rejected(new ValidationError(
                    ErrorCode.INVALID_ARGUMENTS, "python-devs", "요청 직렬화 실패: " + e.getMessage()));
        }

        ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-m", "waste_sim.mcp_bridge")
                .directory(new File(pythonProjectRoot))
                .redirectErrorStream(false);
        try {
            Process p = pb.start();
            try (var out = p.getOutputStream()) {
                out.write(requestJson.getBytes(StandardCharsets.UTF_8));
            }
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return ToolResult.rejected(new ValidationError(
                        ErrorCode.EXECUTION_ERROR, "python-devs",
                        "Python 엔진 실행이 " + timeoutSeconds + "초를 초과해 중단했습니다."));
            }
            if (p.exitValue() != 0) {
                log.warn("python-devs 실행 실패(exit={}): {}", p.exitValue(), stderr.trim());
                return ToolResult.rejected(new ValidationError(
                        ErrorCode.EXECUTION_ERROR, "python-devs", "실행 실패: " + stderr.trim()));
            }
            JsonNode result = MAPPER.readTree(stdout);
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.error("python-devs 어댑터 오류", e);
            return ToolResult.rejected(new ValidationError(
                    ErrorCode.EXECUTION_ERROR, "python-devs", e.getMessage()));
        }
    }

    /** mcp_bridge.py가 기대하는 필드명(McpToolCatalog RUN_SIM_SCHEMA와 동일)으로 직렬화. */
    private String toBridgeJson(SimulationConfig cfg) throws Exception {
        var node = MAPPER.createObjectNode();
        node.put("collectionTime", cfg.getCollectionTimeLabel());
        node.put("days", cfg.getDays());
        node.put("seeds", cfg.getSeeds());
        node.put("numBuildings", cfg.getNumBuildings());
        node.put("residentsPerBuilding", cfg.getResidentsPerBuilding());
        node.put("leaveSigma", cfg.getLeaveSigma());
        node.put("wasteSigma", cfg.getWasteSigma());
        node.put("capacity", cfg.getCapacity());
        node.put("threshold", cfg.getThreshold());
        if (cfg.getOccupationMix() != null && !cfg.getOccupationMix().isEmpty()) {
            var mix = MAPPER.createArrayNode();
            cfg.getOccupationMix().forEach(mix::add);
            node.set("occupationMix", mix);
        }
        return MAPPER.writeValueAsString(node);
    }
}
