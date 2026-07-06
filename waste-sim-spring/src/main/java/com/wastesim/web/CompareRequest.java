package com.wastesim.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * /api/simulation/compare 요청 DTO — 기존의 Map + 무방비 캐스트를 대체한다.
 * 타입 불일치 시 Jackson이 400(HttpMessageNotReadableException)으로 처리하므로
 * 500(ClassCastException)이 발생하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompareRequest {
    private List<String> times = List.of("10:00", "12:00", "14:00");
    private int days = 30;
    private int seeds = 30;
    private double leaveSigma = 30.0;

    public List<String> getTimes() { return times; }
    public void setTimes(List<String> v) { if (v != null && !v.isEmpty()) this.times = v; }
    public int getDays() { return days; }
    public void setDays(int v) { this.days = v; }
    public int getSeeds() { return seeds; }
    public void setSeeds(int v) { this.seeds = v; }
    public double getLeaveSigma() { return leaveSigma; }
    public void setLeaveSigma(double v) { this.leaveSigma = v; }
}
