package com.districtsim.model;

import java.util.Locale;

/**
 * 구역의 주거 유형. 구역 하나는 한 유형이다(아파트 단지 구역, 단독주택 구역 …).
 *
 * <p><b>세대당 배출량은 가정이다.</b> 포항 북구 행정동별 실측을 구하지 못했다. 능력 카드
 * {@code quality.calibration} 에 {@code measured:false} 로 적는다.
 *
 * @param kgPerHouseholdPerDay 세대(상가는 점포)당 하루 평균 배출량
 * @param serviceKgPerMinute   차량이 이 구역에서 1분에 실을 수 있는 양 — 아파트는 거점 수거함이라 빠르고,
 *                             단독주택은 문전 수거라 느리다
 */
public enum HousingType {

    Apartment("아파트", 1.0, 60.0),
    Detached("단독주택", 1.3, 25.0),
    Studio("원룸", 0.6, 35.0),
    Commercial("상가", 3.0, 40.0);

    public final String label;
    public final double kgPerHouseholdPerDay;
    public final double serviceKgPerMinute;

    HousingType(String label, double kgPerHouseholdPerDay, double serviceKgPerMinute) {
        this.label = label;
        this.kgPerHouseholdPerDay = kgPerHouseholdPerDay;
        this.serviceKgPerMinute = serviceKgPerMinute;
    }

    public static HousingType fromName(String name) {
        if (name != null) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (HousingType t : values()) if (t.name().toLowerCase(Locale.ROOT).equals(key)) return t;
        }
        throw new IllegalArgumentException("알 수 없는 주거 유형: " + name
                + " (허용: Apartment, Detached, Studio, Commercial)");
    }
}
