package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.model.SimulationConfig;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MCP_모델_연결_방법.md에 따라 연결한 Python/pyevsim 참조 구현 어댑터의
 * 실제 서브프로세스 호출 테스트. 이 프로젝트 밖(adev-master)에 대한 실제
 * 의존이라, 그 경로가 이 머신에 없으면(CI 등) 조용히 스킵한다 — 이 클래스
 * 자체의 배선(직렬화·타임아웃·오류 처리)은 여기서만 검증한다.
 */
class PythonWasteSimAdapterTest {

    private static final String DEFAULT_PROJECT_ROOT = "C:\\Dev\\project\\python-project\\adev-master";

    private PythonWasteSimAdapter adapter() {
        PythonWasteSimAdapter a = new PythonWasteSimAdapter();
        ReflectionTestUtils.setField(a, "pythonExecutable", "python");
        ReflectionTestUtils.setField(a, "pythonProjectRoot", DEFAULT_PROJECT_ROOT);
        ReflectionTestUtils.setField(a, "timeoutSeconds", 60L);
        return a;
    }

    @Test
    void modelMetadata() {
        PythonWasteSimAdapter a = adapter();
        assertEquals("python-devs", a.modelId());
        assertEquals("run_waste_simulation_devs", a.toolName());
        assertNotNull(a.inputSchemaJson());
    }

    @Test
    void schemaDoesNotAdvertiseJavaOnlyModes() throws Exception {
        JsonNode properties = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(adapter().inputSchemaJson()).path("properties");
        assertFalse(properties.has("travelTimeMode"));
        assertFalse(properties.has("collectionDaysOfWeek"));
        assertFalse(properties.has("dischargeTimeMode"));
        assertTrue(properties.has("wasteMeanKg"));
    }

    @Test
    void rejectsJavaOnlyTravelModeInsteadOfSilentlyRunningLegacy() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setTravelTimeMode("ZONE_PROXY_HYBRID");

        ToolResult r = adapter().run(cfg);

        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("travelTimeMode")),
                r.errors().toString());
    }

    @Test
    void runsRealPythonEngineWhenAvailable() {
        assumeTrue(new File(DEFAULT_PROJECT_ROOT, "waste_sim").isDirectory(),
                "waste_sim 프로젝트가 이 머신에 없어 스킵");

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");
        cfg.setDays(2);
        cfg.setSeeds(2);

        ToolResult r = adapter().run(cfg);
        assertTrue(r.ready(), () -> "python-devs 실행 실패: " + r.errors());
        assertNotNull(r.result());
        String json = r.result().toString();
        assertTrue(json.contains("python-devs"));
        assertTrue(json.contains("totalComplaintsMean"));
    }

    @Test
    void forwardsTrafficParamsToPythonEngine() {
        assumeTrue(new File(DEFAULT_PROJECT_ROOT, "waste_sim").isDirectory(),
                "waste_sim 프로젝트가 이 머신에 없어 스킵");

        // 정체 피크(13시)에 trafficEnabled=true로 돌리면 avgCompletionMinutes가
        // 0보다 커야 한다(이동시간이 실제로 소비됐다는 뜻) — mcp_bridge.py가
        // trafficEnabled/routeTravelMinutes를 무시하고 있었다면 이 값은 항상 0.
        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("13:00");
        cfg.setDays(3);
        cfg.setSeeds(3);
        cfg.setTrafficEnabled(true);
        cfg.setTrafficProfileId("jangryang-weekday");

        ToolResult r = adapter().run(cfg);
        assertTrue(r.ready(), () -> "python-devs 실행 실패: " + r.errors());
        String json = r.result().toString();
        assertTrue(json.contains("\"trafficEnabled\":true"), json);
        assertTrue(json.contains("avgCompletionMinutesMean"), json);
    }

    @Test
    void reportsExecutionErrorOnBadProjectRoot() {
        PythonWasteSimAdapter a = adapter();
        ReflectionTestUtils.setField(a, "pythonProjectRoot", DEFAULT_PROJECT_ROOT + "\\does-not-exist");
        ReflectionTestUtils.setField(a, "timeoutSeconds", 10L);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");

        ToolResult r = a.run(cfg);
        assertFalse(r.ready());
    }

    @Test
    void rejectsCapacityFieldsUnsupportedByPythonReferenceEngine() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setRouteAvailableCapacityKg(800.0);
        cfg.setInitialTruckLoadKg(200.0);

        ToolResult r = adapter().run(cfg);

        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> "routeAvailableCapacityKg".equals(e.field())));
    }

    // ── 서브프로세스 배선 회귀 ────────────────────────────────────────────────
    //
    // 아래 둘은 adev-master가 없는 머신에서도 돈다 — 진짜 엔진 대신 임시 폴더에
    // 같은 이름의 모듈(waste_sim.mcp_bridge)을 심어, 어댑터가 자식 프로세스를
    // 다루는 방식만 떼어 검증한다. 스트림 펌프를 넣기 전에는 둘 다 통과가 아니라
    // 영원히 멈춰서 @Timeout으로 실패했다.

    /** 멈춘 채 아무것도 내보내지 않는 가짜 브리지. */
    private static final String HANGING_BRIDGE = """
            import time
            time.sleep(600)
            """;

    /** stderr를 파이프 버퍼보다 훨씬 많이 쏟아낸 뒤 정상 JSON을 stdout에 내는 가짜 브리지. */
    private static final String STDERR_FLOOD_BRIDGE = """
            import sys
            sys.stdin.read()
            sys.stderr.write('x' * 262144)
            sys.stderr.flush()
            sys.stdout.write('{"engine": "python-devs", "totalComplaintsMean": 1.5}')
            """;

    /** python이 PATH에 없는 머신에서는 아래 두 테스트를 돌릴 수 없다. */
    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code <root>/waste_sim/mcp_bridge.py}를 만들어, 어댑터가 부르는 그 모듈 자리에 꽂는다. */
    private static void writeFakeBridge(Path root, String source) throws Exception {
        Path pkg = root.resolve("waste_sim");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("__init__.py"), "", StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("mcp_bridge.py"), source, StandardCharsets.UTF_8);
    }

    private static PythonWasteSimAdapter adapterAt(Path root, long timeoutSeconds) {
        PythonWasteSimAdapter a = new PythonWasteSimAdapter();
        ReflectionTestUtils.setField(a, "pythonExecutable", "python");
        ReflectionTestUtils.setField(a, "pythonProjectRoot", root.toString());
        ReflectionTestUtils.setField(a, "timeoutSeconds", timeoutSeconds);
        return a;
    }

    /**
     * 멈춘 자식 프로세스는 timeoutSeconds 안에 강제 종료되고 EXECUTION_ERROR로 돌아와야 한다.
     *
     * <p>예전 구현은 waitFor(timeout)보다 <b>먼저</b> stdout을 readAllBytes()로 읽었다.
     * 블로킹이라 자식이 멈추면 거기서 갇히고 타임아웃 분기에는 도달조차 못 했다 —
     * timeoutSeconds가 몇이든 이 호출은 영원히 돌아오지 않았다.
     */
    @Test
    @Timeout(30)
    void enforcesTimeoutWhenChildProcessHangs(@TempDir Path root) throws Exception {
        assumeTrue(pythonAvailable(), "python이 PATH에 없어 스킵");
        writeFakeBridge(root, HANGING_BRIDGE);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");

        long startedAt = System.nanoTime();
        ToolResult r = adapterAt(root, 3L).run(cfg);
        long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L;

        assertFalse(r.ready(), "멈춘 프로세스인데 성공으로 처리됐다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("초과")),
                () -> "타임아웃 사유가 아니다: " + r.errors());
        assertTrue(elapsedSeconds < 20,
                () -> "타임아웃(3초)이 걸리지 않고 " + elapsedSeconds + "초나 걸렸다");
    }

    /**
     * stderr를 파이프 버퍼보다 많이 쏟아내도 stdout의 JSON을 정상적으로 읽어야 한다.
     *
     * <p>예전 구현은 stdout을 끝까지 읽은 <b>뒤에야</b> stderr를 읽었다. 자식이 stderr
     * 파이프 버퍼(OS 기본 수십 KB)를 채우면 자식은 stderr 쓰기에서, 이쪽은 stdout
     * 읽기에서 막혀 서로를 영원히 기다린다 — 파이썬 트레이스백에 경고가 몇 줄 겹치면
     * 실제로 닿는 크기다. 여기서는 256KB를 흘려보내 그 상황을 확정적으로 재현한다.
     */
    @Test
    @Timeout(60)
    void doesNotDeadlockWhenChildFloodsStderr(@TempDir Path root) throws Exception {
        assumeTrue(pythonAvailable(), "python이 PATH에 없어 스킵");
        writeFakeBridge(root, STDERR_FLOOD_BRIDGE);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");

        ToolResult r = adapterAt(root, 30L).run(cfg);

        assertTrue(r.ready(), () -> "stderr 폭주에 막혀 실패했다: " + r.errors());
        assertEquals(1.5, ((JsonNode) r.result()).path("totalComplaintsMean").asDouble());
    }
}
