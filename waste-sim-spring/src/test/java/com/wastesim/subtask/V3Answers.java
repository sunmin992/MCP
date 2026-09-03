package com.wastesim.subtask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3 세트(33개)의 수집 단계 질문 31개에 대한 <b>완전하고 유효한</b> 답변 한 벌.
 *
 * <p>{@link V2Answers}와 같은 역할이고, 두 벌을 함께 두는 이유는 v2를 지우지 않기
 * 때문이다 — 그 세트로 진행된 세션 기록을 되짚을 수 있어야 한다(NFR-20).
 *
 * <p>이 묶음은 <b>논문 기준선</b>이다(4동 25명, 매일 08:30 수거, 교통 미반영, 상수
 * 이동시간). v3에서 질문이 33개로 줄었어도 "관련 없는 항목도 생략하지 않고 묻되 해당
 * 없음으로 받는다"는 규약은 그대로라, 단일 실행에서 쓰이지 않는 항목(다회 수거·구역 내
 * 이동시간 등)이 해당 없음으로 채워져 있는 것이 의도다.
 */
final class V3Answers {

    private V3Answers() {}

    static final String NA = "해당 없음";

    /** 수집 단계 31개를 모두 채운 답변(확인 단계 ST-032·033은 미리보기가 채운다). */
    static Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        // 1단계 — 실행할 실험 선택
        m.put("ST-001", "민원이 가장 적은 수거 시각 찾기");
        m.put("ST-002", "single-run");
        m.put("ST-003", "java");
        // 2단계 — 대상 규모와 주민
        m.put("ST-004", 4);
        m.put("ST-005", 25);
        m.put("ST-006", "BALANCED");
        m.put("ST-007", 30);
        m.put("ST-008", 10);
        // 3단계 — 배출과 수거장
        m.put("ST-009", 0.9);
        m.put("ST-010", 0.3);
        m.put("ST-011", 30);
        m.put("ST-012", "PAPER_BASELINE");
        m.put("ST-013", NA);
        m.put("ST-014", 30);
        m.put("ST-015", 80);
        // 4단계 — 수거 일정과 차량
        m.put("ST-016", "08:30");
        m.put("ST-017", NA);
        m.put("ST-018", "EVERY_DAY");
        m.put("ST-019", "LARGE_5TON");
        m.put("ST-020", 1);
        m.put("ST-021", NA);
        m.put("ST-022", 0);
        m.put("ST-023", NA);
        // 5단계 — 경로와 교통
        m.put("ST-024", "NONE");
        m.put("ST-025", NA);
        m.put("ST-026", "LEGACY_CONSTANT");
        m.put("ST-027", 8);
        m.put("ST-028", NA);
        m.put("ST-029", NA);
        m.put("ST-030", NA);
        // 6단계 — 설정 확인
        m.put("ST-031", "ALL");
        return m;
    }

    /** 기본 묶음에서 일부만 바꾼 사본. */
    static Map<String, Object> with(String id, Object value) {
        Map<String, Object> m = all();
        m.put(id, value);
        return m;
    }

    /** 기본 묶음에서 두 항목을 바꾼 사본(조합 조건을 만들 때). */
    static Map<String, Object> with(String id, Object value, String id2, Object value2) {
        Map<String, Object> m = with(id, value);
        m.put(id2, value2);
        return m;
    }

    /** 기본 묶음에서 한 항목을 뺀 사본(미충족 상황을 만들 때). */
    static Map<String, Object> without(String id) {
        Map<String, Object> m = all();
        m.remove(id);
        return m;
    }
}
