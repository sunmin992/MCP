package com.districtsim.model;

import java.util.Locale;

/**
 * 수거일이 공휴일일 때. {@code SKIP} 은 그날 수거를 건너뛰고, {@code NEXT_DAY} 는 다음 날(수거일이 아니어도)
 * 대신 수거한다. 다음 날도 공휴일이면 또 미룬다.
 */
public enum HolidayPolicy {

    SKIP, NEXT_DAY;

    public static HolidayPolicy fromName(String name) {
        if (name != null) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            for (HolidayPolicy p : values()) if (p.name().equals(key)) return p;
        }
        throw new IllegalArgumentException("알 수 없는 공휴일 정책: " + name + " (허용: SKIP, NEXT_DAY)");
    }
}
