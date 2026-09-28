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

    /** times 키가 요청 본문에 실려 왔지만 비어 있었는가 — 미지정(기본값)과 구분한다. */
    private boolean timesExplicitlyEmpty = false;

    public List<String> getTimes() { return times; }

    /**
     * times를 설정한다. <b>빈 배열은 기본값으로 대체하지 않는다</b>(A-02 해소).
     *
     * <p>Jackson은 키가 본문에 있을 때만 이 setter를 부른다. 그러므로 여기 들어온
     * 빈 배열·null은 "지정하지 않음"이 아니라 "비어 있는 값을 지정함"이다. 예전처럼
     * 조용히 기본값 3종(10:00·12:00·14:00)으로 바꿔 200을 돌려주면, 클라이언트는
     * 자기가 보낸 times가 통째로 비었다는 것을 영영 알 수 없고 <b>요청하지도 않은
     * 시각의 결과</b>를 자기 요청의 답으로 읽는다 — D-26(조용한 보정 금지)이 막으려는
     * 상황 그대로다. 대신 {@link #isTimesExplicitlyEmpty()}로 표시해 두고 컨트롤러가
     * 400 VALIDATION으로 거부한다.
     */
    public void setTimes(List<String> v) {
        if (v == null || v.isEmpty()) {
            this.timesExplicitlyEmpty = true;
            return;
        }
        this.timesExplicitlyEmpty = false;
        this.times = v;
    }

    /** @return times가 명시적으로 빈 값으로 들어왔으면 true — 호출부가 400으로 거부한다. */
    public boolean isTimesExplicitlyEmpty() { return timesExplicitlyEmpty; }

    public int getDays() { return days; }
    public void setDays(int v) { this.days = v; }
    public int getSeeds() { return seeds; }
    public void setSeeds(int v) { this.seeds = v; }
    public double getLeaveSigma() { return leaveSigma; }
    public void setLeaveSigma(double v) { this.leaveSigma = v; }
}
