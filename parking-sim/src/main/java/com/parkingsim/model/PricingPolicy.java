package com.parkingsim.model;

import java.util.Locale;

/** 요금 정책. 시간대 차등이면 혼잡 시간대(08–10시, 17–19시)에 할증률만큼 더 받는다. */
public enum PricingPolicy {

    FLAT, TIME_OF_DAY, FREE;

    /** 할증이 붙는 시간대인가. 시(0–23) 기준. */
    public static boolean isPeakHour(int hour) {
        return (hour >= 8 && hour < 10) || (hour >= 17 && hour < 19);
    }

    public static PricingPolicy fromName(String name) {
        if (name != null) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            for (PricingPolicy p : values()) if (p.name().equals(key)) return p;
        }
        throw new IllegalArgumentException("알 수 없는 요금 정책: " + name
                + " (허용: FLAT, TIME_OF_DAY, FREE)");
    }
}
