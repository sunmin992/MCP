package com.wastesim.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 온도 진폭(안정성) 지표 검증 — 교수님이 새로 제시한 평가 축("온도가 출렁이면 부품 수명이
 * 줄어든다")을 정식 지표({@code tempAmplitudeC})로 노출한 것을 회귀로 고정한다.
 *
 * <p>핵심 세 성질: (1) 상수 부하면 진폭이 거의 0이고 시변 부하에서만 커진다,
 * (2) 질량 큰 방열판(2노드 열용량)이 진폭을 눌러 준다 — 이게 "R_ja 순위와 안정성 순위가
 * 어긋날 수 있다"는 연구 질문의 근거다, (3) 워밍업 램프를 진폭으로 오인하지 않는다.
 */
class TempAmplitudeTest {

    private final ThermalSimulator sim = new ThermalSimulator();
    private final AiLoadProfileService loads = new AiLoadProfileService();
    private final ObjectMapper om = new ObjectMapper();

    /** 스로틀링이 없는 조건(active 25℃)을 골라 부하 진동만 깨끗하게 본다. */
    private ThermalParams cool() {
        return ThermalParams.preset(BoardType.PI5, CoolingPreset.ACTIVE, 25.0);
    }

    private ThermalSimulator.Spec spec(ThermalParams p, AiLoadProfile load, HeatsinkMass hs, double loadSec) {
        return new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, load, hs);
    }

    @Test
    @DisplayName("상수 부하는 진폭≈0, burst 시변 부하는 진폭이 뚜렷하다")
    void constantIsFlatBurstOscillates() {
        double steady = sim.run(BoardType.PI5, spec(cool(), loads.find("steady"), null, 1800)).tempAmplitudeC();
        double burst  = sim.run(BoardType.PI5, spec(cool(), loads.find("burst"), null, 1800)).tempAmplitudeC();

        assertTrue(steady < 1.0, "상수 부하는 정착 후 거의 평평해야 한다: " + steady);
        assertTrue(burst > steady + 2.0, "burst는 주기적 출렁임이 잡혀야 한다: burst=" + burst + " steady=" + steady);
    }

    @Test
    @DisplayName("질량 큰 방열판(2노드)이 온도 진폭을 눌러 준다 — 안정성 순위의 근거")
    void heavyHeatsinkDampensAmplitude() {
        double rIn = 1.5;   // Pi5 rJc — 전체(active 2.6)보다 작아야 한다
        HeatsinkMass light = new HeatsinkMass(20, rIn);
        HeatsinkMass heavy = new HeatsinkMass(400, rIn);

        double lightAmp = sim.run(BoardType.PI5, spec(cool(), loads.find("burst"), light, 3600)).tempAmplitudeC();
        double heavyAmp = sim.run(BoardType.PI5, spec(cool(), loads.find("burst"), heavy, 3600)).tempAmplitudeC();

        assertTrue(heavyAmp < lightAmp,
                "질량이 크면 피크를 흡수해 진폭이 작아야 한다: heavy=" + heavyAmp + " light=" + lightAmp);
    }

    @Test
    @DisplayName("워밍업 램프(0→정상)를 진폭으로 오인하지 않는다 — 정착 구간만 본다")
    void warmupRampIsNotCountedAsAmplitude() {
        // 상수 부하인데 시작 온도가 낮으면 전반부에 큰 상승 램프가 있다. 그래도 정착 구간(후반)만
        // 보므로 진폭은 작아야 한다 — 램프 전체(수십 ℃)가 진폭으로 새면 이 테스트가 깨진다.
        ThermalParams cold = cool().withStartTemp(25.0);
        double amp = sim.run(BoardType.PI5, spec(cold, loads.find("steady"), null, 1800)).tempAmplitudeC();
        assertTrue(amp < 1.0, "워밍업 램프가 진폭으로 새면 안 된다: " + amp);
    }

    @Test
    @DisplayName("진폭 지표가 결과 metrics와 notes에 노출된다")
    void amplitudeSurfacedInOutput() {
        ThermalRun run = sim.run(BoardType.PI5, spec(cool(), loads.find("burst"), null, 1800));
        Map<String, Object> m = SimulateEdgeThrottlingTool.metrics(run);
        assertTrue(m.containsKey("tempAmplitudeC"), "metrics에 tempAmplitudeC가 있어야 한다");
        assertTrue(m.containsKey("loadSettledMeanTempC"));
        assertEquals(run.tempAmplitudeC(), m.get("tempAmplitudeC"));

        String notes = String.join("\n", run.notes());
        assertTrue(notes.contains("진폭"), "진폭을 설명하는 note가 있어야 한다:\n" + notes);
    }

    @Test
    @DisplayName("방열판 배치 비교 결과에도 후보별 진폭이 나온다(안정성으로 순위 매기기)")
    void heatsinkRankingExposesAmplitude() throws Exception {
        // 배치 비교를 시변 부하로 돌리면 각 후보의 tempAmplitudeC가 함께 나와야 한다.
        SimulateHeatsinkLayoutTool tool = new SimulateHeatsinkLayoutTool(
                new EdgeThermalProfileStore(), loads);
        String json = "{\"board\":\"pi5\",\"ambientTempC\":30,\"aiLoadProfileId\":\"burst\",\"maxFps\":30,"
                + "\"layouts\":[{\"name\":\"기준\",\"heatsink\":{\"baseLengthMm\":40,\"baseWidthMm\":40,\"finCount\":12}}]}";
        var res = tool.call(om.readTree(json));
        assertTrue(res.ready(), () -> String.valueOf(res.errors()));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.result();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertTrue(ranking.stream().anyMatch(r -> r.containsKey("tempAmplitudeC")),
                "방열판 후보별로 tempAmplitudeC가 나와야 안정성으로 비교할 수 있다");
    }
}
