package com.wastesim.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteDurationQueryDetectorTest {

    @Test
    void detectsRouteDurationQuery() {
        List<String> route = List.of("Node_A", "Node_C", "Node_B", "Node_D");
        assertTrue(RouteDurationQueryDetector.isRouteDurationQuery(
                "Node_A, Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?", route));
    }

    @Test
    void requiresAtLeastTwoNodes() {
        assertFalse(RouteDurationQueryDetector.isRouteDurationQuery(
                "Node_A만 방문하면 얼마나 걸려?", List.of("Node_A")));
        assertFalse(RouteDurationQueryDetector.isRouteDurationQuery(
                "얼마나 걸려?", null));
    }

    @Test
    void requiresDurationPhrase() {
        // 방문 순서는 있지만 소요시간 질문이 아님(예: 실행 요청) — 오탐 방지
        List<String> route = List.of("Node_A", "Node_C");
        assertFalse(RouteDurationQueryDetector.isRouteDurationQuery(
                "Node_A, Node_C 순서로 13시에 수거해줘", route));
    }

    @Test
    void variousPhraseWordings() {
        List<String> route = List.of("Node_A", "Node_B");
        assertTrue(RouteDurationQueryDetector.isRouteDurationQuery("Node_A, Node_B 소요 시간 알려줘", route));
        assertTrue(RouteDurationQueryDetector.isRouteDurationQuery("Node_A, Node_B 순서면 몇 분 걸리나요?", route));
    }

    /** 실측 회귀 케이스 — "걸리는 시간"으로 물으면 시각이 함께 있어도(예:
     *  "14시에 수거하면") 경로 소요시간 질의로 인식해야 한다. 이게 안 되면
     *  ChatController가 이 분기를 못 타고 전체 시뮬레이션(민원 집계)이
     *  잘못 실행된다. */
    @Test
    void detectsGeollineunSiganWording() {
        List<String> route = List.of("Node_A", "Node_B", "Node_D");
        assertTrue(RouteDurationQueryDetector.isRouteDurationQuery(
                "소형트럭으로 Node_A, Node_B, Node_D 순서로 방문해서 14시에 수거하면 걸리는 시간 알려줘",
                route));
    }
}
