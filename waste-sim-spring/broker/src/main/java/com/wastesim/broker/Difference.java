package com.wastesim.broker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 후보끼리 다른 항목 하나.
 *
 * @param item   항목 이름 — region · unit · calibration · population:&lt;요청 구절&gt;
 * @param values 서버 id → 그 서버의 값. 후보 순서(서버 id 오름차순)를 지킨다
 */
public record Difference(String item, Map<String, String> values) {

    public Difference {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
