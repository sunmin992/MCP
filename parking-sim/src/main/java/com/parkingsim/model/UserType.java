package com.parkingsim.model;

import java.util.Locale;

/**
 * 공영주차장 이용자 유형. 유형마다 도착 시각 분포 · 체류 시간 · 요금 민감도가 다르다.
 *
 * <p><b>모든 값은 가정이다.</b> 포항 도심 공영주차장의 실측 도착 분포를 구하지 못했다.
 * 능력 카드 {@code quality.calibration} 에 {@code measured:false} 로 적는다.
 *
 * @param arrivalMeanMinutes   도착 시각 평균(자정 기준 분). 균등 분포면 구간 시작
 * @param arrivalSpreadMinutes 정규 분포면 표준편차, 균등 분포면 구간 길이
 * @param uniformArrival       도착을 균등 분포로 뽑는가
 * @param meanStayMinutes      평균 체류 시간
 * @param priceElasticity      요금 회피 민감도. 30분당 1,000원에서 회피 확률이 이 값이 된다
 */
public enum UserType {

    Commuter("출퇴근", 8 * 60 + 15, 30, false, 540, 0.20),
    Shopper("쇼핑", 11 * 60, 8 * 60, true, 90, 0.60),
    Visitor("방문", 10 * 60, 7 * 60, true, 120, 0.40),
    Resident("거주자", 19 * 60, 45, false, 720, 0.10);

    public final String label;
    public final int arrivalMeanMinutes;
    public final int arrivalSpreadMinutes;
    public final boolean uniformArrival;
    public final int meanStayMinutes;
    public final double priceElasticity;

    UserType(String label, int arrivalMeanMinutes, int arrivalSpreadMinutes, boolean uniformArrival,
             int meanStayMinutes, double priceElasticity) {
        this.label = label;
        this.arrivalMeanMinutes = arrivalMeanMinutes;
        this.arrivalSpreadMinutes = arrivalSpreadMinutes;
        this.uniformArrival = uniformArrival;
        this.meanStayMinutes = meanStayMinutes;
        this.priceElasticity = priceElasticity;
    }

    /** 이름 → 유형. 대소문자 무관. 모르면 예외 — 가까운 값으로 바꾸지 않는다. */
    public static UserType fromName(String name) {
        if (name != null) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (UserType t : values()) {
                if (t.name().toLowerCase(Locale.ROOT).equals(key)) return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 이용자 유형: " + name
                + " (허용: Commuter, Shopper, Visitor, Resident)");
    }
}
