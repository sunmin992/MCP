package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code <asset-id>::<input-field>} 규약이 한 자리에만 있는가.
 * 규약이 복제되면 한 곳만 고쳐도 컴파일이 통과하고, 통과한 뒤에 다른 자산의 값이
 * 조용히 잘못된 자리에 들어간다.
 */
class ParameterIdTest {

    @Test
    void 자산과_필드를_합쳐_매개변수_ID를_만든다() {
        assertEquals("sim::days", ParameterId.of("sim", "days"));
    }

    @Test
    void 마지막_경계_뒤가_필드명이다() {
        assertEquals("days", ParameterId.fieldOf("sim::days"));
        assertEquals("routeTravelMinutes",
                ParameterId.fieldOf("backupSim::depotA::routeTravelMinutes"));
    }

    @Test
    void 경계가_없으면_전체가_필드명이고_자산은_없다() {
        assertEquals("days", ParameterId.fieldOf("days"));
        assertNull(ParameterId.assetOf("days"));
    }

    @Test
    void 마지막_경계_앞이_자산이다() {
        assertEquals("sim", ParameterId.assetOf("sim::days"));
        assertEquals("backupSim::depotA",
                ParameterId.assetOf("backupSim::depotA::routeTravelMinutes"));
    }

    @Test
    void 자산이나_필드가_비면_ID를_만들지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> ParameterId.of(" ", "days"));
        assertThrows(IllegalArgumentException.class, () -> ParameterId.of("sim", null));
    }
}
