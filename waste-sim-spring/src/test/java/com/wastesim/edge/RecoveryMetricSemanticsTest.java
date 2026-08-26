package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 회복 지표의 <b>의미</b>를 고정하는 회귀 테스트(부록 B.2 E-06 결정 반영).
 *
 * <p>결정 내용: {@code trtServiceSec}라는 이름은 "서비스가 회복된 시각"으로 읽히지만 실제로
 * 재는 것은 <i>낼 수 있게 된</i> 시각(잠재 처리능력)이다. 이름을 그대로 바꾸면 공개된 MCP
 * 응답 계약이 깨지므로, 기존 필드는 값을 유지한 채 deprecated로 두고 의미가 분명한 두 필드를
 * 새로 낸다.
 *
 * <ul>
 *   <li>{@code trtServiceCapacitySec} — 잠재 처리능력 회복(기존 계산과 같은 값)</li>
 *   <li>{@code trtObservedServiceSec} — 실제 관측 FPS 회복. 회복 구간에 부하를 멈추거나
 *       낮추는 정책(R1·R2)에서는 <b>null</b>이며, 그 null이 곧 답이다</li>
 * </ul>
 *
 * <p>여기서 고정하는 것은 숫자가 아니라 <b>어느 필드가 무엇을 재는가</b>다. 이 성질이 깨지면
 * 같은 응답을 읽는 클라이언트가 정반대의 결론을 낸다.
 */
class RecoveryMetricSemanticsTest {

    private final ThermalSimulator sim = new ThermalSimulator();

    /** 스로틀링을 확실히 유발한 뒤 회복시키는 조건 — Pi5 무냉각 최대 처리량. */
    private ThermalRun run(RecoveryPolicy policy) {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 35.0);
        double rJa = policy == RecoveryPolicy.R3_ACTIVE_COOLING
                ? BoardType.PI5.rJaKPerW(CoolingPreset.ACTIVE) : p.rJaKPerW();
        return sim.run(BoardType.PI5, new ThermalSimulator.Spec(
                p, WorkloadMode.MAX_THROUGHPUT, 30.0, 1200, policy, 900, rJa, 0.2, 5.0, true));
    }

    @Test
    @DisplayName("구 필드 trtServiceSec은 trtServiceCapacitySec과 항상 같은 값이다 — 하위호환")
    void deprecatedFieldMirrorsCapacity() {
        for (RecoveryPolicy policy : new RecoveryPolicy[]{
                RecoveryPolicy.R1_STOP, RecoveryPolicy.R2_LOW_LOAD, RecoveryPolicy.R3_ACTIVE_COOLING}) {
            ThermalRun r = run(policy);
            assertEquals(r.trtServiceCapacitySec(), r.trtServiceSec(),
                    policy + ": 기존 클라이언트가 읽던 값이 바뀌면 안 된다");
        }
    }

    @Test
    @DisplayName("R1(추론 완전 중지)은 처리능력은 회복되지만 실측 FPS 회복은 잴 수 없다")
    void r1HasCapacityRecoveryButNoObservedRecovery() {
        ThermalRun r1 = run(RecoveryPolicy.R1_STOP);

        assertNotNull(r1.trtServiceCapacitySec(),
                "부하를 멈추면 클럭이 풀리므로 '낼 수 있는 FPS'는 반드시 회복된다");
        assertNull(r1.trtObservedServiceSec(),
                "회복 구간에 추론을 아예 멈추므로 실제 FPS는 0이다 — 없는 값을 "
                        + "처리능력으로 대신 채우면 E-06이 그대로 되살아난다(D-26)");
    }

    @Test
    @DisplayName("R2(저부하 25% 유지)도 기준선의 90%에 닿지 못해 실측 FPS 회복은 null이다")
    void r2StaysBelowServiceThreshold() {
        ThermalRun r2 = run(RecoveryPolicy.R2_LOW_LOAD);

        assertNotNull(r2.trtServiceCapacitySec(), "클럭은 풀린다");
        assertNull(r2.trtObservedServiceSec(),
                "25% 부하는 기준선의 90%에 구조적으로 도달할 수 없다 — 정책의 성질이지 결함이 아니다");
    }

    @Test
    @DisplayName("MCP 응답에 세 필드가 모두 실리고, 값이 없으면 키는 있고 값이 null이다")
    void mcpResponseCarriesAllThreeFields() {
        var m = SimulateEdgeThrottlingTool.metrics(run(RecoveryPolicy.R1_STOP));

        assertTrue(m.containsKey("trtServiceSec"), "구 필드는 계속 실린다(하위호환)");
        assertTrue(m.containsKey("trtServiceCapacitySec"));
        assertTrue(m.containsKey("trtObservedServiceSec"),
                "값이 null이어도 키는 있어야 한다 — 키가 없으면 클라이언트가 '측정 안 함'과 "
                        + "'측정했지만 도달 못 함'을 구별할 수 없다");
        assertNull(m.get("trtObservedServiceSec"));
        assertEquals(m.get("trtServiceCapacitySec"), m.get("trtServiceSec"));
    }

    @Test
    @DisplayName("R3(팬 100%·서비스 유지)는 실측 FPS 회복까지 관측된다 — 정책 간 차이가 지표로 드러난다")
    void r3RecoversObservedService() {
        ThermalRun r3 = run(RecoveryPolicy.R3_ACTIVE_COOLING);

        assertNotNull(r3.trtServiceCapacitySec());
        assertNotNull(r3.trtObservedServiceSec(),
                "R3는 회복 구간에도 부하를 유지하므로 실제 FPS로 회복을 잴 수 있다 — "
                        + "이것이 R1·R2와 R3를 가르는 실질적 차이다");
        assertTrue(r3.trtObservedServiceSec() >= r3.trtServiceCapacitySec(),
                "실제로 내는 것은 낼 수 있게 된 다음이다 — 순서가 뒤집히면 계산이 잘못된 것이다");
    }
}
