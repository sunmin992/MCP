package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시나리오 실험의 범용 결과 — 프론트에서 다중 라인/막대 차트로 렌더링.
 *
 * x축 카테고리(xCategories) × 여러 계열(series) 구조로 5종 실험을 모두 표현한다.
 *  - 구성 비교: x=수거시각, series=시나리오(대학가/공단/가족/균형)
 *  - 수거 sweep: x=수거시각, series=단일
 *  - 행동 변동: x=외출분산 α, series=배출변동 β
 *  - 인프라: x=용량 C, series=임계 θ
 *  - 밀도: x=건물×인원, series=단일
 */
public class ScenarioResponse {

    private String scenarioType;
    private String title;
    private String xLabel;
    private String yLabel = "월 평균 민원";
    private List<String> xCategories = new ArrayList<>();
    private List<Series> series = new ArrayList<>();
    private List<Map<String, Object>> insights = new ArrayList<>();

    public static class Series {
        private String name;
        private List<Double> values = new ArrayList<>();
        private List<Double> stds = new ArrayList<>();

        public Series() {}
        public Series(String name) { this.name = name; }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public List<Double> getValues() { return values; }
        public void setValues(List<Double> v) { this.values = v; }
        public List<Double> getStds() { return stds; }
        public void setStds(List<Double> v) { this.stds = v; }

        public void add(double mean, double std) { values.add(mean); stds.add(std); }
    }

    public ScenarioResponse() {}
    public ScenarioResponse(String type, String title, String xLabel) {
        this.scenarioType = type;
        this.title = title;
        this.xLabel = xLabel;
    }

    /** 새 계열을 추가하고 반환 */
    public Series newSeries(String name) {
        Series s = new Series(name);
        series.add(s);
        return s;
    }

    /** insight(요약) 한 줄 추가 */
    public void addInsight(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        insights.add(m);
    }

    public void addInsight(Map<String, Object> m) { insights.add(m); }

    // Getters & setters
    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String v) { this.scenarioType = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    @JsonProperty("xLabel")
    public String getXLabel() { return xLabel; }
    public void setXLabel(String v) { this.xLabel = v; }
    @JsonProperty("yLabel")
    public String getYLabel() { return yLabel; }
    public void setYLabel(String v) { this.yLabel = v; }
    @JsonProperty("xCategories")
    public List<String> getXCategories() { return xCategories; }
    public void setXCategories(List<String> v) { this.xCategories = v; }
    public List<Series> getSeries() { return series; }
    public void setSeries(List<Series> v) { this.series = v; }
    public List<Map<String, Object>> getInsights() { return insights; }
    public void setInsights(List<Map<String, Object>> v) { this.insights = v; }
}
