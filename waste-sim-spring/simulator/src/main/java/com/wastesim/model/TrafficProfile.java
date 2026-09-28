package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.Set;

/**
 * 포항시 교통량 데이터 — 시간대별·구역별 혼잡 가중치.
 *
 * <p><b>이 클래스는 이제 교통량만 담는다.</b> v1.12까지는 {@code alleyNodeIds}(대형 차량
 * 진입 불가 지점)도 함께 들고 있었는데, 그건 교통량이 아니라 <b>지점의 물리적 성질</b>이라
 * 여기 있을 것이 아니었다. 실측 가중치와 같은 파일에 있어 측정치처럼 보이는 문제도 있었고,
 * 실제로 확정된 좌표와 대조하니 골목으로 표시된 두 곳이 각각 4차로 교차로와 6차로 도로변이었다.
 * 접근성은 {@code CollectionSite.largeTruckAllowed}로 옮겼다.
 * (TRAFFIC_EXTENSION_DESIGN.md §2.1). {@code src/main/resources/traffic/*.json}에서
 * {@link com.wastesim.service.TrafficDataService}가 로드한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrafficProfile {

    private String id;
    private double[] hourlyWeight;                  // 길이 24, 시간대별 통행시간 배수
    private java.util.Map<String, double[]> nodeHourlyWeight;  // 노드별 시간대 가중치(선택)
    private double congestionThresholdRed = 2.0;     // RED(극심) 판정 가중치

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public double[] getHourlyWeight() { return hourlyWeight; }
    public void setHourlyWeight(double[] v) { this.hourlyWeight = v; }

    public java.util.Map<String, double[]> getNodeHourlyWeight() { return nodeHourlyWeight; }
    public void setNodeHourlyWeight(java.util.Map<String, double[]> v) { this.nodeHourlyWeight = v; }

    public double getCongestionThresholdRed() { return congestionThresholdRed; }
    public void setCongestionThresholdRed(double v) { this.congestionThresholdRed = v; }

    /** 특정 분(minuteOfDay)·노드의 혼잡 가중치. 노드별 데이터 없으면 전역 시간대 가중치 사용. */
    public double weightAt(int minuteOfDay, String node) {
        int hour = ((minuteOfDay % 1440) + 1440) % 1440 / 60;
        double[] table = (nodeHourlyWeight != null && node != null) ? nodeHourlyWeight.get(node) : null;
        if (table == null) table = hourlyWeight;
        if (table == null || table.length == 0) return 1.0;
        return table[hour % table.length];
    }

    public boolean isRed(int minuteOfDay, String node) {
        return weightAt(minuteOfDay, node) >= congestionThresholdRed;
    }
}
