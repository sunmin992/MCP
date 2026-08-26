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
    @DisplayName("'냉각장치'도 능동 냉각 부품으로 읽는다 — 팬·쿨러와 같은 자리")
    void coolingDeviceWordIsRecognized() {
        // 실측 회귀(UI 라이브): "방열판, 냉각장치 없이 pi4로 주변 온도 28도로 3시간 돌리면
        // 스로틀링 언제 발생해"가 bare가 아니라 passive로 실행됐다. BOTH_ABSENT가 팬·쿨러만
        // 알고 '냉각장치'를 몰라서, 목록형 부정이 통째로 빗나가고 PASSIVE의 "방열판"만 걸렸다.
        // 그 결과 정상상태 63.7℃(방열판 있음)가 나와 소프트 제한조차 진입하지 않았고,
        // 사용자가 물은 "스로틀링 시점"에 답할 수 있는 값이 아예 생기지 않았다.
        assertEquals("bare", cooling("방열판, 냉각장치 없이 pi4로 주변 온도 28도로 3시간 돌리면 스로틀링 언제 발생해"));
        assertEquals("bare", cooling("방열판이나 냉각장치 없이 돌려줘"));
        assertEquals("bare", cooling("냉각장치나 방열판 없이"));
        assertEquals("bare", cooling("방열판, 냉각 모듈 없이"));
    }

    @Test
    @DisplayName("'냉각장치'만 부정하면 방열판은 유지 — '팬 없이'와 같은 규칙")
    void coolingDeviceOnlyAbsentStaysPassive() {
        // 방열판을 함께 부정하지 않았으므로 무냉각으로 내리지 않는다. 이 말만으로는
        // "능동 냉각만 빼겠다"는 뜻인지 "아무것도 안 붙이겠다"는 뜻인지 알 수 없고,
        // 기존 "팬 없이" 규칙이 이미 전자를 택하고 있다.
        assertEquals("passive", cooling("냉각장치 없이 돌려줘"));
        assertEquals("passive", cooling("냉각장치를 빼면"));
    }

    @Test
    @DisplayName("'장치'·'모듈'은 '냉각'이 앞에 붙을 때만 냉각 부품으로 본다 — 과잉 매칭 방지")
    void bareDeviceWordDoesNotCount() {
        // "측정장치"·"계측 모듈"까지 냉각 부품으로 읽으면, 실험 장비를 설명한 문장이
        // 무냉각 실행으로 둔갑한다.
        assertEquals("passive", cooling("방열판 붙이고 측정장치 없이 돌려줘"));
        assertEquals("passive", cooling("방열판 달고 계측 모듈 없이"));
    }

    @Test
    @DisplayName("일반 냉각 표현은 그대로 — 회귀 방지")
    void plainCoolingUnchanged() {
        assertEquals("bare", cooling("무냉각으로"));
        assertEquals("passive", cooling("방열판만 붙이면"));
        assertEquals("active", cooling("팬 냉각으로"));
    }
}
