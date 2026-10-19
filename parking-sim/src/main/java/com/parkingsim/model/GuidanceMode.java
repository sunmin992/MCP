package com.parkingsim.model;

import java.util.Locale;

/**
 * 주차 안내 방식.
 *
 * <ul>
 *   <li>{@code NONE} — 만차인지 들어가 봐야 안다. 만차 주차장 안을 한 바퀴 도는 시간이 든다.</li>
 *   <li>{@code STATIC_SIGN} — 주차장 입구 안내판이 만차를 알린다. 들어가 돌지는 않지만, 다음 주차장은
 *       가 봐야 안다.</li>
 *   <li>{@code REALTIME_APP} — 갱신 주기마다 모든 주차장의 빈자리를 안다. 마지막 갱신 때 빈자리가 있던
 *       가장 가까운 주차장으로 바로 간다. 갱신 사이에 찰 수 있다.</li>
 * </ul>
 *
 * <p>주차장이 한 곳이면 갈 곳이 없으므로 안내 방식이 결과를 바꾸지 못한다.
 */
public enum GuidanceMode {

    NONE, STATIC_SIGN, REALTIME_APP;

    public static GuidanceMode fromName(String name) {
        if (name != null) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            for (GuidanceMode g : values()) if (g.name().equals(key)) return g;
        }
        throw new IllegalArgumentException("알 수 없는 안내 방식: " + name
                + " (허용: NONE, STATIC_SIGN, REALTIME_APP)");
    }
}
