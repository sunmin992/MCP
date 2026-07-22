package com.wastesim.mcp;

import com.wastesim.model.SimulationConfig;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;

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
    void reportsExecutionErrorOnBadProjectRoot() {
        PythonWasteSimAdapter a = adapter();
        ReflectionTestUtils.setField(a, "pythonProjectRoot", DEFAULT_PROJECT_ROOT + "\\does-not-exist");
        ReflectionTestUtils.setField(a, "timeoutSeconds", 10L);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");

        ToolResult r = a.run(cfg);
        assertFalse(r.ready());
    }
}
