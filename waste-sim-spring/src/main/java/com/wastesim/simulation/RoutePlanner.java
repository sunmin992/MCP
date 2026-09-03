package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 엔진과 검증기가 공유하는 방문 순서 해석·트럭별 round-robin 배정 규칙.
 * 외부 데이터 조회나 시뮬레이션 실행 없이 동일 설정에서 동일 경로를 만든다.
 */
public final class RoutePlanner {

    private RoutePlanner() {}

    /**
     * 트럭 순서대로 실제 방문하는 건물 인덱스 목록을 반환한다.
     *
     * <p>유효한 routeSequence는 대소문자와 관계없이 지정 순서를 유지한다. 미지정·빈 목록
     * 또는 잘못된 순열은 기존 엔진처럼 자연 순서로 폴백한다. 이 방어적 폴백은 잘못된 입력을
     * 허용한다는 뜻이 아니며, 사용자 요청은 검증기의 V-T4가 별도로 거부한다.
     *
     * <p>건물보다 많은 트럭의 뒤쪽 빈 경로는 만들지 않는다. 해당 트럭은 기존 엔진에서도
     * 운행·용량·결과 집계에서 제외됐으므로 트럭 번호와 실제 운행 결과는 그대로다.
     */
    public static List<List<Integer>> assignRoutes(SimulationConfig config) {
        int buildings = config.getNumBuildings();
        if (buildings < 1 || buildings > 26) {
            throw new IllegalArgumentException("경로를 배정할 건물 수는 1~26이어야 합니다.");
        }
        int trucks = Math.min(buildings, Math.max(1, config.getTruckCount()));
        List<List<Integer>> routes = new ArrayList<>();
        for (int i = 0; i < trucks; i++) routes.add(new ArrayList<>());
        List<Integer> order = resolveVisitOrder(config.getRouteSequence(), buildings);
        for (int i = 0; i < order.size(); i++) routes.get(i % trucks).add(order.get(i));
        return routes.stream().map(List::copyOf).toList();
    }

    private static List<Integer> resolveVisitOrder(List<String> requested, int buildings) {
        List<Integer> natural = new ArrayList<>();
        for (int b = 0; b < buildings; b++) natural.add(b);
        if (requested == null || requested.isEmpty() || requested.size() != buildings) return natural;

        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String site : requested) {
            int index = nodeIndex(site);
            if (index < 0 || index >= buildings || !seen.add(index)) return natural;
            order.add(index);
        }
        return order;
    }

    /** 건물 인덱스 → 노드 id. */
    public static String nodeId(int buildingIndex) {
        return "Node_" + (char) ('A' + buildingIndex);
    }

    /** 노드 id → 건물 인덱스. 형식이 잘못됐으면 -1. */
    public static int nodeIndex(String nodeId) {
        if (nodeId == null || !nodeId.matches("(?i)Node_[A-Za-z]")) return -1;
        return Character.toUpperCase(nodeId.charAt(5)) - 'A';
    }
}
