package com.wastesim.edge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 냉각 조건의 <b>부정 표현</b> 파싱 — 특히 "방열판이나 냉각팬 없이"처럼 방열판과 팬을
 * 함께 부정하는 목록형이 무냉각(bare)으로 잡히는지 회귀로 고정한다.
 *
 * <p>실측 회귀(UI 라이브): "pi4에 방열판이나 냉각팬 없이 …"가 FAN_ABSENT("냉각팬…없")에만
 * 걸려 "팬만 없다 → 방열판 유지(passive)"로 처리됐다. "없이"가 방열판까지 부정하는 걸
 * 놓친 것이다. 화면에 방열판이 그려지고 정상상태 69.6℃가 나와, 사용자가 물은 무냉각과
 * 다른 실험이 조용히 실행됐다.
 */
class CoolingNegationTest {

    private String cooling(String text) {
        ObjectNode n = EdgeParamGuard.fromText(text);
        return n.hasNonNull("cooling") ? n.get("cooling").asText() : "(미지정)";
    }

    @Test
    @DisplayName("방열판과 팬을 함께 부정하면 무냉각(bare)이다")
    void bothAbsentIsBare() {
        assertEquals("bare", cooling("pi4에 방열판이나 냉각팬 없이 최대 처리량으로 3시간 돌리면"));
        assertEquals("bare", cooling("방열판이나 냉각팬 없이"));
        assertEquals("bare", cooling("냉각팬이나 방열판 없이 돌려줘"));
        assertEquals("bare", cooling("방열판, 팬 없이"));
        assertEquals("bare", cooling("방열판도 팬도 없이"));
    }

    @Test
    @DisplayName("팬만 부정하고 방열판 언급이 없으면 방열판 유지(passive) — 기존 동작 보존")
    void fanOnlyAbsentStaysPassive() {
        assertEquals("passive", cooling("팬 없이 돌려줘"));
        assertEquals("passive", cooling("팬을 끄고"));
    }

    @Test
    @DisplayName("방열판이 있는 채로 팬만 빼면 방열판 유지 — '없이'가 팬에만 걸린다")
    void heatsinkPresentFanRemovedStaysPassive() {
        assertEquals("passive", cooling("방열판 달고 팬 없이"));
        assertEquals("passive", cooling("방열판 붙이고 냉각팬만 빼면"));
    }

    @Test
    @DisplayName("일반 냉각 표현은 그대로 — 회귀 방지")
    void plainCoolingUnchanged() {
        assertEquals("bare", cooling("무냉각으로"));
        assertEquals("passive", cooling("방열판만 붙이면"));
        assertEquals("active", cooling("팬 냉각으로"));
    }
}
