package com.wastesim.subtask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 세트(50개)의 수집 단계 질문 47개에 대한 <b>완전하고 유효한</b> 답변 한 벌.
 *
 * <p>테스트마다 47개를 다시 적으면 어느 테스트가 무엇을 바꿔 보고 있는지 묻히고, 세트가
 * 개정될 때 고칠 자리가 흩어진다. 여기 한 벌을 두고 각 테스트는 <b>자기가 검증하려는
 * 항목만</b> 덮어쓴다.
 *
 * <p>"해당 없음"이 섞여 있는 것이 의도다 — v2는 관련 없는 항목도 생략하지 않고 묻되
 * 그 답을 정식으로 받는다. 단일 실행(single-run)에서는 비교·최적화 항목이 실제로 해당
 * 없으므로, 이 기본 묶음이 곧 그 규약의 표본이다.
 */
final class V2Answers {

    private V2Answers() {}

    static final String NA = "해당 없음";

    /** 수집 단계 47개를 모두 채운 답변(확인 단계 ST-048~050은 미리보기가 채운다). */
    static Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        // 1단계 — 목적과 대상 지역
        m.put("ST-001", "민원이 가장 적은 수거 시각 찾기");
        m.put("ST-002", List.of("collectionTime"));
        m.put("ST-003", "포항시 북구 장량동 원룸촌");
        m.put("ST-004", 4);
        m.put("ST-005", List.of("Node_A", "Node_B", "Node_C", "Node_D"));
        // 2단계 — 기간과 주민
        m.put("ST-006", 30);
        m.put("ST-007", "minute");
        m.put("ST-008", "none");
        m.put("ST-009", 100);
        m.put("ST-010", "Node_A=25, Node_B=25, Node_C=25, Node_D=25");
        m.put("ST-011", "BlueCollar=0.25, Student=0.35, Housewife=0.15, NightShift=0.10, OfficeWorker=0.15");
        m.put("ST-012", 30);
        // 3단계 — 배출량과 수거장
        m.put("ST-013", 0.9);
        m.put("ST-014", 0.3);
        m.put("ST-015", "GENERAL=0.5, FOOD=0.2, RECYCLING=0.3");
        m.put("ST-016", "NONE");
        m.put("ST-017", 30);
        m.put("ST-018", 80);
        m.put("ST-019", "ZERO");
        // 4단계 — 수거 일정과 차량
        m.put("ST-020", "08:30");
        m.put("ST-021", NA);
        m.put("ST-022", 1);
        m.put("ST-023", "ALL");
        m.put("ST-024", "LARGE_5TON");
        m.put("ST-025", 1);
        m.put("ST-026", "기본값 사용");
        m.put("ST-027", 0);
        m.put("ST-028", NA);
        // 5단계 — 경로와 교통
        m.put("ST-029", "NONE");
        m.put("ST-030", NA);
        m.put("ST-031", 8);
        m.put("ST-032", List.of("Node_A", "Node_B", "Node_C", "Node_D"));
        m.put("ST-033", "FIXED");
        // 6단계 — 실행·비교 정책
        m.put("ST-034", "SINGLE");
        m.put("ST-035", "single-run");
        m.put("ST-036", NA);
        m.put("ST-037", NA);
        m.put("ST-038", NA);
        // 7단계 — 결과 지표와 실행 방법
        m.put("ST-039", List.of("totalComplaints"));
        m.put("ST-040", NA);
        m.put("ST-041", "SUMMARY");
        m.put("ST-042", "java");
        m.put("ST-043", 10);
        m.put("ST-044", "SERVER_DEFAULT");
        // 8단계 — 입력 확인
        m.put("ST-045", "제공 데이터 없음");
        m.put("ST-046", "ALL");
        m.put("ST-047", "CONFIRMED");
        return m;
    }

    /** 기본 묶음에서 일부만 바꾼 사본. */
    static Map<String, Object> with(String id, Object value) {
        Map<String, Object> m = all();
        m.put(id, value);
        return m;
    }

    /** 기본 묶음에서 한 항목을 뺀 사본(미충족 상황을 만들 때). */
    static Map<String, Object> without(String id) {
        Map<String, Object> m = all();
        m.remove(id);
        return m;
    }
}
