package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.Set;

/**
 * 포항시 교통량 데이터 — 시간대별·구역별 혼잡 가중치.
 * (TRAFFIC_EXTENSION_DESIGN.md §2.1). {@code src/main/resources/traffic/*.json}에서
 * {@link com.wastesim.service.TrafficDataService}가 로드한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrafficProfile {

    private String id;
    private double[] hourlyWeight;                  // 길이 24, 시간대별 통행시간 배수
    private java.util.Map<String, double[]> nodeHourlyWeight;  // 노드별 시간대 가중치(선택)
    private double congestionThresholdRed = 2.0;     // RED(극심) 판정 가중치
    /**
     * 이면도로(골목) 노드 id 집합 — V-T3(대형트럭 골목 진입 불가) 판정에 사용.
     * 설계서에 이 데이터의 출처가 명시되지 않아, TrafficProfile이 지역의 정적
     * 공간 정보(혼잡도와 마찬가지로 지역 고유 데이터)도 함께 갖는 것으로 간주해
     * 이 필드를 추가했다 — 시드 JSON의 alleyNodeIds로 채운다.
     */
    private Set<String> alleyNodeIds = Collections.emptySet();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public double[] getHourlyWeight() { return hourlyWeight; }
    public void setHourlyWeight(double[] v) { this.hourlyWeight = v; }

    public java.util.Map<String, double[]> getNodeHourlyWeight() { return nodeHourlyWeight; }
    public void setNodeHourlyWeight(java.util.Map<String, double[]> v) { this.nodeHourlyWeight = v; }

    public double getCongestionThresholdRed() { return congestionThresholdRed; }
    public void setCongestionThresholdRed(double v) { this.congestionThresholdRed = v; }

    public Set<String> getAlleyNodeIds() { return alleyNodeIds; }
    public void setAlleyNodeIds(Set<String> v) { this.alleyNodeIds = v == null ? Collections.emptySet() : v; }

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
