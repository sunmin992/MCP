package com.wastesim.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteAwarenessDetectorTest {

    @Test
    void extractsUnderscoreForm() {
        assertEquals(List.of("Node_A", "Node_C", "Node_B", "Node_D"),
                RouteAwarenessDetector.extractRouteSequence(
                        "Node_A, Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?"));
    }

    /** 실측 회귀 — 사용자가 실제로 친 표기. 밑줄 없이 공백으로 띄우고, 나머지
     *  노드는 접두어 없이 쉼표로만 나열했다. 이게 안 잡히면 경로가 null이 되어
     *  ChatController가 경로 소요시간 게이트를 못 타고 "수거 시각을 알려달라"고
     *  엉뚱하게 되묻는다. */
    @Test
    void extractsSpacedPrefixWithBareLetterList() {
        assertEquals(List.of("Node_A", "Node_D", "Node_C", "Node_B"),
                RouteAwarenessDetector.extractRouteSequence(
                        "소형 트럭으로 node A,D,C,B 순서로 방문하면 얼마나 걸려"));
    }

    @Test
    void extractsVariousSeparators() {
        assertEquals(List.of("Node_A", "Node_B"),
                RouteAwarenessDetector.extractRouteSequence("nodeA nodeB 순서"));
        assertEquals(List.of("Node_A", "Node_B", "Node_C"),
                RouteAwarenessDetector.extractRouteSequence("Node A → B → C"));
    }

    @Test
    void normalizesToUpperCase() {
        assertEquals(List.of("Node_A", "Node_B"),
                RouteAwarenessDetector.extractRouteSequence("node a, b 순서로"));
    }

    /** 낱글자 흡수는 노드 토큰 "바로 뒤"에 이어질 때만 — 문장 아무 데나 있는
     *  쉼표+한 글자를 주워오면 오탐이 된다. */
    @Test
    void doesNotSwallowUnrelatedLetters() {
        assertEquals(List.of("Node_A"),
                RouteAwarenessDetector.extractRouteSequence("Node_A 순서로 방문, B 지점은 제외해줘"));
    }

    @Test
    void returnsNullWhenNoNodeMentioned() {
        assertNull(RouteAwarenessDetector.extractRouteSequence("12시에 수거하면 민원이 몇 건이야?"));
        assertNull(RouteAwarenessDetector.extractRouteSequence(null));
    }

    @Test
    void detectsTruckTypeMention() {
        assertTrue(RouteAwarenessDetector.truckTypeMentioned("소형 트럭으로 실행해줘"));
        assertFalse(RouteAwarenessDetector.truckTypeMentioned("12시에 수거해줘"));
    }
}
