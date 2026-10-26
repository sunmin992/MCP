package com.countersim.model;

/** 줄 서는 방식. */
public enum QueueDiscipline {
    /** 번호표 한 줄 — 비는 창구가 다음 사람을 부른다. */
    SINGLE_LINE,
    /** 창구마다 줄 — 도착할 때 가장 짧은 줄에 서고 옮기지 않는다. */
    PER_COUNTER;

    public static QueueDiscipline fromName(String name) {
        for (QueueDiscipline q : values()) {
            if (q.name().equalsIgnoreCase(String.valueOf(name))) return q;
        }
        throw new IllegalArgumentException("알 수 없는 줄 서는 방식: " + name + " (허용: SINGLE_LINE, PER_COUNTER)");
    }
}
