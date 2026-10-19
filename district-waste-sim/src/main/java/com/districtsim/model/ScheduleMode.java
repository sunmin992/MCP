package com.districtsim.model;

import java.util.List;
import java.util.Locale;

/**
 * 수거 일정. 요일 번호는 0=월 … 6=일이고, 시뮬레이션 0일차는 시작 날짜의 요일이다.
 *
 * <ul>
 *   <li>{@code POHANG_BUKGU} — 포항 북구 생활쓰레기 수거 요일(월 · 화 · 목 · 금). 행정안전부 생활쓰레기
 *       배출정보 표준데이터의 배출 요일(일 · 월 · 수 · 목)에 하루를 더한 것 — 배출 창이 20:00~06:00 이라
 *       밤에 내놓고 다음 날 새벽에 수거된다고 읽었다. <b>이 읽기는 해석이다.</b></li>
 *   <li>{@code DAILY} — 일요일을 뺀 매일.</li>
 *   <li>{@code ALTERNATE} — 월 · 수 · 금.</li>
 *   <li>{@code WEEKDAYS} — 사용자가 고른 요일.</li>
 * </ul>
 */
public enum ScheduleMode {

    POHANG_BUKGU(List.of(0, 1, 3, 4)),
    DAILY(List.of(0, 1, 2, 3, 4, 5)),
    ALTERNATE(List.of(0, 2, 4)),
    WEEKDAYS(List.of());

    /** 정해진 요일. {@code WEEKDAYS} 는 비어 있고 설정의 요일 목록을 쓴다. */
    public final List<Integer> days;

    ScheduleMode(List<Integer> days) {
        this.days = days;
    }

    public static ScheduleMode fromName(String name) {
        if (name != null) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            for (ScheduleMode m : values()) if (m.name().equals(key)) return m;
        }
        throw new IllegalArgumentException("알 수 없는 수거 일정: " + name
                + " (허용: POHANG_BUKGU, DAILY, ALTERNATE, WEEKDAYS)");
    }

    /** 요일 이름(MON…SUN) → 번호(0…6). 모르면 예외. */
    public static int dayIndex(String name) {
        List<String> names = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
        int i = name == null ? -1 : names.indexOf(name.trim().toUpperCase(Locale.ROOT));
        if (i < 0) throw new IllegalArgumentException("알 수 없는 요일: " + name + " (허용: " + names + ")");
        return i;
    }
}
