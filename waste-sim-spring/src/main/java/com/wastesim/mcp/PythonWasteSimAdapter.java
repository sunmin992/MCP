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
import java.io.IOException;
import java.io.InputStream;
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

    /** destroyForcibly() 뒤에 자식이 실제로 사라지기를 기다리는 상한(초). */
    private static final long KILL_GRACE_SECONDS = 5;

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
        if (cfg.getRouteAvailableCapacityKg() != null || cfg.getInitialTruckLoadKg() != 0.0) {
            return ToolResult.rejected(new ValidationError(
                    ErrorCode.INVALID_ARGUMENTS, "routeAvailableCapacityKg",
                    "Python 참조 엔진은 routeAvailableCapacityKg/initialTruckLoadKg를 지원하지 않습니다. "
                            + "이 필드는 Java 엔진에서 실행하세요."));
        }
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
            // stdout·stderr를 각각 별도 스레드로 동시에 비운다. 예전엔 stdout을
            // readAllBytes()로 끝까지 읽고 → stderr를 읽고 → 그제서야 waitFor(timeout)을
            // 불렀는데, 그 순서 때문에 두 가지가 동시에 깨져 있었다.
            //
            // (1) 타임아웃이 아예 동작하지 않았다 — readAllBytes()는 블로킹이라 자식이
            //     멈추면 거기서 무한 대기하고, 아래 waitFor(timeoutSeconds)에는 영영 도달하지
            //     못한다. 설정된 180초는 사실상 아무 역할도 하지 않았다.
            // (2) 파이프 데드락 — stdout을 다 읽을 때까지 stderr를 손대지 않으므로,
            //     자식이 stderr 파이프 버퍼(OS 기본 수십 KB)를 채우면 자식은 stderr 쓰기에서
            //     막히고 이쪽은 stdout 읽기에서 막혀 서로를 영원히 기다린다. 파이썬
            //     트레이스백에 경고가 몇 줄만 겹쳐도 닿는 크기다.
            //
            // redirectErrorStream(true)로 둘을 합치는 방법은 쓸 수 없다 — mcp_bridge.py는
            // stdout에 JSON 한 줄만 낸다는 계약이라, 경고 한 줄만 섞여도 아래의
            // MAPPER.readTree가 깨진다.
            StreamPump outPump = StreamPump.start("python-devs-stdout", p.getInputStream());
            StreamPump errPump = StreamPump.start("python-devs-stderr", p.getErrorStream());

            try (var out = p.getOutputStream()) {
                out.write(requestJson.getBytes(StandardCharsets.UTF_8));
            }

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                // 죽은 것까지 확인해야 스트림이 닫혀 펌프 스레드가 EOF를 본다.
                // 여기서도 무한 대기하지 않는다 — 이 메서드에는 경계 없는 블로킹이
                // 하나도 남아 있으면 안 된다.
                p.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
                log.warn("python-devs 실행 타임아웃({}초) — stderr: {}",
                        timeoutSeconds, errPump.awaitText().trim());
                return ToolResult.rejected(new ValidationError(
                        ErrorCode.EXECUTION_ERROR, "python-devs",
                        "Python 엔진 실행이 " + timeoutSeconds + "초를 초과해 중단했습니다."));
            }
            String stdout = outPump.awaitText();
            String stderr = errPump.awaitText();
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

    /** mcp_bridge.py가 기대하는 필드명(McpToolCatalog RUN_SIM_SCHEMA와 동일)으로 직렬화.
     *
     * <p>trafficEnabled/trafficProfileId/routeTravelMinutes도 전달한다 — waste_sim이
     * 오늘(장량동 실측 교통량 반영) 확장되면서 Java 엔진과 같은 필드명으로 이
     * 세 값을 받아들이게 됐다({@code build_and_run(traffic_enabled=..., ...)}).
     * Python 쪽은 프로파일이 "jangryang-weekday" 하나뿐이라 trafficProfileId
     * 값 자체는 쓰지 않고 trafficEnabled만으로 그 기본 프로파일을 켠다(D-09와
     * 같은 이유로 다른 id가 와도 거부하지 않고 기본 프로파일로 폴백 — Python
     * 쪽은 mcp_bridge.py가 경고를 남긴다).
     */
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
        node.put("trafficEnabled", cfg.isTrafficEnabled());
        if (cfg.getTrafficProfileId() != null) {
            node.put("trafficProfileId", cfg.getTrafficProfileId());
        }
        if (cfg.getRouteTravelMinutes() > 0) {
            node.put("routeTravelMinutes", cfg.getRouteTravelMinutes());
        }
        return MAPPER.writeValueAsString(node);
    }

    /**
     * 서브프로세스의 출력 스트림 하나를 전용 스레드로 끝까지 읽어 두는 펌프.
     *
     * <p>stdout·stderr를 동시에 비워야 하는 이유는 {@link #run(SimulationConfig)}의
     * 주석을 참고. 읽기 도중 오류가 나면 빈 문자열을 돌려준다 — 이 값은 진단용
     * 텍스트이지 실행 결과 자체가 아니라, 부분 실패 때문에 성공한 실행을 실패로
     * 바꿀 이유가 없다.
     */
    private static final class StreamPump {

        /** 프로세스가 끝난 뒤 스레드가 EOF를 보고 정리될 때까지 기다리는 상한(ms). */
        private static final long JOIN_TIMEOUT_MS = 5_000;

        private final Thread thread;
        /** 펌프 스레드가 쓰고 호출 스레드가 읽는다 — join()이 happens-before를 보장하지만,
         *  join이 타임아웃으로 끝나는 경우까지 안전하려면 volatile이여야 한다. */
        private volatile String text = "";

        private StreamPump(String name, InputStream in) {
            this.thread = new Thread(() -> {
                try (InputStream s = in) {
                    text = new String(s.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.debug("{} 읽기 실패: {}", name, e.getMessage());
                }
            }, name);
            this.thread.setDaemon(true);   // 서버 종료를 막지 않는다
        }

        static StreamPump start(String name, InputStream in) {
            StreamPump pump = new StreamPump(name, in);
            pump.thread.start();
            return pump;
        }

        /** 읽기가 끝날 때까지(최대 {@link #JOIN_TIMEOUT_MS}) 기다린 뒤 내용을 돌려준다. */
        String awaitText() throws InterruptedException {
            thread.join(JOIN_TIMEOUT_MS);
            return text;
        }
    }
}
