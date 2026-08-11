package com.wastesim.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EdgeToolSelector} 단독 검증. 이 클래스는 도구를 고르는 유일한 지점인데
 * 전용 테스트가 없어서, "방열판"이 냉각 조건으로 쓰인 문장이 배치 비교로 새는 버그가
 * 그대로 통과했다(UI 라이브 테스트로 발견). 그 케이스를 회귀로 고정한다.
 */
class EdgeToolSelectorTest {

    // ── 냉각 조건으로서의 "방열판" — 배치 비교가 아니라 발열 시뮬레이션이다 ──────
    //
    // 실측 재현: 아래 문장들이 전부 simulate_heatsink_layout 으로 가서, 사용자가 물은
    // TTT 대신 후보 7종 순위표가 돌아왔다. "방열판만"을 "팬 냉각"으로 바꾼 같은 문장은
    // 정상 동작했기 때문에 원인이 그 단어 하나임이 드러났다.

    @Test
    @DisplayName("'방열판만 상태에서 언제 스로틀링' 은 발열 시뮬레이션이다")
    void heatsinkAsCoolingConditionRoutesToThrottling() {
        String[] throttlingRequests = {
                "라즈베리파이 5를 방열판만 상태에서 목표 FPS 고정으로 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘. 주변 온도는 40도야.",
                "라즈베리파이 4를 방열판만 상태에서 목표 FPS 고정으로 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘. 주변 온도는 30도야.",
                "방열판 상태에서 20분 돌리면 몇 도까지 올라가?",
                "방열판만 붙이면 스로틀링 안 걸려?",
                "히트싱크 달고 30분 돌려줘",
        };
        for (String text : throttlingRequests) {
            assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(text),
                    "냉각 조건이지 배치 비교 대상이 아니다: " + text);
        }
    }

    @Test
    @DisplayName("냉각 조건이 팬·무냉각일 때와 동일하게 라우팅돼야 한다(대조군)")
    void coolingConditionDoesNotChangeToolChoice() {
        // 같은 질문에서 냉각 조건만 바꾼 문장들 — 전부 같은 도구로 가야 한다.
        String fan = "라즈베리파이 5를 팬 냉각 상태에서 목표 FPS 고정으로 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘.";
        String passive = "라즈베리파이 5를 방열판만 상태에서 목표 FPS 고정으로 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘.";
        String bare = "라즈베리파이 5를 무냉각 상태에서 목표 FPS 고정으로 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘.";

        assertEquals(EdgeToolSelector.select(fan), EdgeToolSelector.select(passive),
                "냉각 조건만 다른 같은 질문이 다른 도구로 가면 안 된다");
        assertEquals(EdgeToolSelector.select(fan), EdgeToolSelector.select(bare));
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(fan));
    }

    @Test
    @DisplayName("보드 비교 요청에 '방열판 상태'가 붙어도 발열 시뮬레이션으로 간다")
    void boardComparisonWithHeatsinkConditionStaysThrottling() {
        // 이 케이스가 배치 도구로 새면 보드가 하나로 확정돼야 해서
        // "어느 보드인지 알려주세요"라는 되물음으로 바뀐다(실측 재현).
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(
                "라즈베리파이 4와 5의 발열 특성이 어떻게 다른지 방열판 상태에서 주변 온도 40도, 50분 기준으로 비교해줘."));
    }

    // ── 배치·형상이 실제 대상일 때는 배치 비교여야 한다 ────────────────────────

    @Test
    @DisplayName("형상·배치를 가리키는 어휘가 있으면 배치 비교다")
    void layoutVocabularyRoutesToHeatsink() {
        String[] layoutRequests = {
                "라즈베리파이 5에서 방열판을 어떤 형상·배치로 붙이는 게 가장 시원한지 비교해줘. 주변 온도는 40도야.",
                "방열판을 어디에 붙이는 게 제일 좋아?",
                "방열판을 어떻게 배치해야 스로틀링이 덜 걸릴까?",
                "핀 방향을 기류와 나란히 하면 얼마나 좋아져?",
                "15mm 어긋나게 붙이면 온도가 얼마나 오르지?",
                "서멀 구리스를 두껍게 바르면?",
                "구리 방열판이 알루미늄보다 나아?",
        };
        for (String text : layoutRequests) {
            assertEquals(EdgeToolSelector.TOOL_HEATSINK, EdgeToolSelector.select(text),
                    "배치·형상 비교여야 한다: " + text);
        }
    }

    // ── 캘리브레이션은 배치·발열보다 우선 ────────────────────────────────────

    @Test
    @DisplayName("실측 보정 요청은 배치 어휘가 섞여도 캘리브레이션이 이긴다")
    void calibrationWinsOverOthers() {
        assertEquals(EdgeToolSelector.TOOL_CALIBRATE,
                EdgeToolSelector.select("측정 데이터로 모델 보정해줘"));
        assertEquals(EdgeToolSelector.TOOL_CALIBRATE,
                EdgeToolSelector.select("실측 CSV로 방열판 배치 모델을 보정하고 싶어"));
    }

    // ── 스윕이 캘리브레이션보다 우선 (FR-78) ─────────────────────────────────
    //
    // FR-78이 정한 검사 순서는 스윕 → 캘리브레이션 → 방열판 배치 → 발열이다.
    // 두 어휘가 함께 나온 문장에서 캘리브레이션이 이기면 사용자는 답을 못 받는다 —
    // 캘리브레이션은 시계열을 채팅으로 실어 나를 수 없어 채팅에서 실행하지 않고
    // 보내는 방법만 안내하기 때문이다(FR-83). 스윕은 그 문장에서 실제로 실행된다.

    @Test
    @DisplayName("스윕 어휘가 있으면 캘리브레이션 어휘가 섞여도 스윕이 이긴다")
    void sweepWinsOverCalibration() {
        assertEquals(EdgeToolSelector.TOOL_SWEEP,
                EdgeToolSelector.select("실측 프로파일 기준으로 최적 팬 rpm 찾아줘"));
        assertEquals(EdgeToolSelector.TOOL_SWEEP,
                EdgeToolSelector.select("보정된 모델로 pwm 스윕 돌려줘"));
        assertEquals(EdgeToolSelector.TOOL_SWEEP,
                EdgeToolSelector.select("측정 데이터 기준으로 팬 몇 rpm이 가성비가 제일 좋아?"));
    }

    @Test
    @DisplayName("단서가 없으면 기본값은 발열 시뮬레이션이다")
    void defaultsToThrottling() {
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select("발열 어때?"));
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select("pi5 무냉각이면 몇 초 만에 스로틀링 걸려?"));
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(null));
    }
}
