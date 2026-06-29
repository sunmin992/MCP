package com.wastesim.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지역 성격별 거주민 구성 프리셋 — 논문의 핵심(occupation_mix).
 *
 * round-robin 배정용 직업 리스트로 비율을 표현한다.
 *   대학가형 : 학생 위주    (학생 7 : 생산직 2 : 주부 1)
 *   공단인근형: 생산직 위주  (생산직 7 : 학생 1 : 주부 2)
 *   가족주거형: 주부 비중 ↑ (주부 5 : 학생 3 : 생산직 2)
 *   균형형   : 균등         (생산직 1 : 학생 1 : 주부 1)
 */
public enum ScenarioPreset {

    UNIVERSITY("대학가형", "학생 위주 (인근 대학)",
            repeat("Student", 7, "BlueCollar", 2, "Housewife", 1)),
    INDUSTRIAL("공단인근형", "생산직(일용직) 위주",
            repeat("BlueCollar", 7, "Student", 1, "Housewife", 2)),
    FAMILY("가족주거형", "전업주부 비중 높음",
            repeat("Housewife", 5, "Student", 3, "BlueCollar", 2)),
    BALANCED("균형형", "직업 균등 (기본 장량동)",
            Arrays.asList("BlueCollar", "Student", "Housewife"));

    public final String labelKo;
    public final String desc;
    public final List<String> mix;

    ScenarioPreset(String labelKo, String desc, List<String> mix) {
        this.labelKo = labelKo;
        this.desc = desc;
        this.mix = mix;
    }

    /** "Student",7,"BlueCollar",2,... → 비율만큼 반복된 리스트 */
    private static List<String> repeat(Object... pairs) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String occ = (String) pairs[i];
            int n = (Integer) pairs[i + 1];
            for (int j = 0; j < n; j++) out.add(occ);
        }
        return out;
    }

    /** 구성 비율을 직업별 백분율 맵으로 환산 (UI 표시용) */
    public Map<String, Integer> ratioPercent() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String o : mix) count.merge(o, 1, Integer::sum);
        Map<String, Integer> pct = new LinkedHashMap<>();
        count.forEach((k, v) -> pct.put(k, Math.round(v * 100f / mix.size())));
        return pct;
    }

    public static ScenarioPreset fromKey(String key) {
        if (key == null) return BALANCED;
        for (ScenarioPreset p : values())
            if (p.name().equalsIgnoreCase(key) || p.labelKo.equals(key)) return p;
        return BALANCED;
    }
}
