package com.wastesim.subtask;

import com.wastesim.tool.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 충분성 판정(FR-130).
 *
 * <p><b>v2에서 규칙이 뒤집혔다.</b> v1은 "필요하지 않은 것은 묻지 않는다"였고, 선택한
 * 시나리오 유형에 따라 질문을 걸러 냈다. v2 명세는 반대다 — <b>관련 없는 항목도 생략하지
 * 않고 묻되, "해당 없음"으로 답을 받는다.</b> 그래서 이 클래스는 더 이상 질문을 고르지
 * 않고, 세트의 수집 단계 질문 전부를 그대로 돌려준다.
 *
 * <p>바뀐 이유는 사용자가 보는 것이 달라지기 때문이다. 질문을 걸러 내면 시나리오 유형을
 * 바꿀 때마다 남은 질문 수가 출렁이고, 사용자는 자기가 몇 개를 더 답해야 하는지 알 수 없다.
 * 전부 묻고 "해당 없음"을 허용하면 진행 표시가 처음부터 끝까지 같은 분모를 갖는다.
 *
 * <p>대신 <b>조합 조건</b>은 여기 남는다. 항목 하나하나는 유효한데 함께 놓으면 성립하지
 * 않는 경우 — 건물별 인원의 합이 총 거주민 수와 다르거나, 교통을 켰는데 프로파일이 없거나,
 * 시드가 하나인데 월별 순위를 묻는 경우 — 는 항목별 검증으로는 잡히지 않는다.
 */
@Component
public class JangnyangCompletenessChecker {

    /** {@code monthly-waste}는 시드가 1개면 순위를 단정하지 않는다(D-40). */
    private static final int MONTHLY_WASTE_MIN_SEEDS = 2;

    /**
     * 이번 실험에서 물어야 할 서브태스크 — <b>수집 단계 전부</b>다.
     *
     * <p>{@code scenarioType} 인자를 더 이상 보지 않는다. 시그니처는 호출부 변경을 줄이려고
     * 남겨 두었고, 시나리오에 따라 질문이 줄어들던 동작은 v2에서 사라졌다.
     */
    public List<JangnyangSubtask> relevantSubtasks(JangnyangSubtaskDefinition def, String scenarioType) {
        return def.collectSubtasks();
    }

    /**
     * 충분한가. 미충족이면 <b>부분 명세를 만들지 않고</b> 조립을 막는다.
     *
     * @param answers 검증을 통과해 누적된 답변들
     */
    public CompletenessVerdict check(JangnyangSubtaskDefinition def,
                                     Map<String, JangnyangSubtaskAnswer> answers) {
        String scenarioType = stringValue(answers, def, "scenarioType");
        List<String> missing = new ArrayList<>();
        List<SubtaskError> reasons = new ArrayList<>();

        // 1) 수집 단계 질문에 하나도 빠짐없이 답했는가. "해당 없음"도 답이다.
        for (JangnyangSubtask s : def.collectSubtasks()) {
            JangnyangSubtaskAnswer a = answers.get(s.id());
            if (a == null || !a.valid()) {
                missing.add(s.id());
                reasons.add(new SubtaskError(s.id(), ErrorCode.MISSING_FIELD,
                        "아직 답하지 않은 항목이다.", s.retryQuestion()));
            }
        }

        // 2) 조합 조건 — 항목별로는 유효한데 함께 놓으면 성립하지 않는 것들.
        addIfPresent(missing, reasons, buildingSumMismatch(def, answers));
        addIfPresent(missing, reasons, nodeCountMismatch(def, answers));
        addIfPresent(missing, reasons, trafficWithoutProfile(def, answers));
        addIfPresent(missing, reasons, truckLoadExceedsCapacity(def, answers));
        addIfPresent(missing, reasons, monthlyWasteNeedsSeeds(def, answers, scenarioType));
        addIfPresent(missing, reasons, assumptionsRejected(def, answers));

        return new CompletenessVerdict(missing.isEmpty(), scenarioType,
                List.copyOf(missing), List.copyOf(reasons));
    }

    private static void addIfPresent(List<String> missing, List<SubtaskError> reasons, SubtaskError e) {
        if (e == null) return;
        missing.add(e.subtaskId());
        reasons.add(e);
    }

    // ── 조합 조건 ──────────────────────────────────────────────────────────

    /** 건물별 인원의 합이 총 거주민 수와 맞는가. 어긋나면 어느 쪽이 맞는지 서버가 정하지 않는다. */
    private static SubtaskError buildingSumMismatch(JangnyangSubtaskDefinition def,
                                                    Map<String, JangnyangSubtaskAnswer> a) {
        Integer total = intValue(a, def, "totalResidents");
        Map<String, Object> perBuilding = mapValue(a, def, "residentsPerBuildingMap");
        if (total == null || perBuilding == null) return null;
        int sum = 0;
        for (Object v : perBuilding.values()) {
            if (v instanceof Number n) sum += n.intValue();
        }
        if (sum == total) return null;
        return error(def, "residentsPerBuildingMap", ErrorCode.OUT_OF_RANGE,
                "건물별 인원의 합이 " + sum + "명인데 총 거주민 수는 " + total
                        + "명이다. 어느 쪽이 맞는지는 서버가 정하지 않는다.");
    }

    /** 수거 지점 개수가 건물 수와 같은가. */
    private static SubtaskError nodeCountMismatch(JangnyangSubtaskDefinition def,
                                                  Map<String, JangnyangSubtaskAnswer> a) {
        Integer buildings = intValue(a, def, "numBuildings");
        List<?> nodes = listValue(a, def, "collectionNodes");
        if (buildings == null || nodes == null) return null;
        if (nodes.size() == buildings) return null;
        return error(def, "collectionNodes", ErrorCode.OUT_OF_RANGE,
                "수거 지점이 " + nodes.size() + "곳인데 건물은 " + buildings + "동이다. 개수가 같아야 한다.");
    }

    /** 교통을 켰는데 프로파일이 "해당 없음"이면 교통 레이어가 조용히 무력화된다. */
    private static SubtaskError trafficWithoutProfile(JangnyangSubtaskDefinition def,
                                                      Map<String, JangnyangSubtaskAnswer> a) {
        String mode = stringValue(a, def, "trafficMode");
        String profile = stringValue(a, def, "trafficProfileId");
        if (mode == null || "NONE".equals(mode)) return null;
        if (profile != null && !JangnyangSubtaskValidator.NOT_APPLICABLE.equals(profile)) return null;
        return error(def, "trafficProfileId", ErrorCode.MISSING_FIELD,
                "교통량을 반영하기로 했으면 프로파일이 있어야 한다. 없으면 교통 레이어가 결과에 반영되지 않는다.");
    }

    /** 출발 시 이미 실린 양이 이번 경로 적재용량보다 많을 수는 없다. */
    private static SubtaskError truckLoadExceedsCapacity(JangnyangSubtaskDefinition def,
                                                         Map<String, JangnyangSubtaskAnswer> a) {
        Double capacity = dblValue(a, def, "routeAvailableCapacityKg");
        Double initial = dblValue(a, def, "initialTruckLoadKg");
        if (capacity == null || initial == null) return null;
        if (initial <= capacity) return null;
        return error(def, "initialTruckLoadKg", ErrorCode.OUT_OF_RANGE,
                "초기 적재량 " + initial + "kg이 경로 적재용량 " + capacity + "kg보다 많다.");
    }

    /** 시드가 하나면 잡음과 계절성을 구분할 수 없어 순위가 난수의 결과가 된다(D-40). */
    private static SubtaskError monthlyWasteNeedsSeeds(JangnyangSubtaskDefinition def,
                                                       Map<String, JangnyangSubtaskAnswer> a,
                                                       String scenarioType) {
        if (!"monthly-waste".equals(scenarioType)) return null;
        Integer seeds = intValue(a, def, "seeds");
        if (seeds == null || seeds >= MONTHLY_WASTE_MIN_SEEDS) return null;
        return error(def, "seeds", ErrorCode.OUT_OF_RANGE,
                "monthly-waste는 시드가 " + MONTHLY_WASTE_MIN_SEEDS
                        + " 이상이어야 한다 — 시드가 하나면 잡음 위에 순위를 세우게 된다(D-40).");
    }

    /** 모델 가정을 확인하지 않았으면 실행하지 않는다. */
    private static SubtaskError assumptionsRejected(JangnyangSubtaskDefinition def,
                                                    Map<String, JangnyangSubtaskAnswer> a) {
        String v = stringValue(a, def, "assumptionApproval");
        if (v == null || "CONFIRMED".equals(v)) return null;
        return error(def, "assumptionApproval", ErrorCode.MISSING_FIELD,
                "모델 가정을 확인해야 실행할 수 있다. 바꿀 값이 있으면 해당 질문으로 돌아가 고친다.");
    }

    // ── 값 꺼내기(답변 필드명 기준) ─────────────────────────────────────────

    private static JangnyangSubtaskAnswer byField(Map<String, JangnyangSubtaskAnswer> a,
                                                  JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtask st = def.byAnswerField(field);
        if (st == null) return null;
        JangnyangSubtaskAnswer v = a.get(st.id());
        return v != null && v.valid() ? v : null;
    }

    private static SubtaskError error(JangnyangSubtaskDefinition def, String field,
                                      ErrorCode code, String reason) {
        JangnyangSubtask st = def.byAnswerField(field);
        return new SubtaskError(st == null ? field : st.id(), code, reason,
                st == null ? "" : st.retryQuestion());
    }

    private static String stringValue(Map<String, JangnyangSubtaskAnswer> a,
                                      JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtaskAnswer v = byField(a, def, field);
        return v == null || v.value() == null ? null : String.valueOf(v.value());
    }

    private static Integer intValue(Map<String, JangnyangSubtaskAnswer> a,
                                    JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtaskAnswer v = byField(a, def, field);
        return v != null && v.value() instanceof Number n ? n.intValue() : null;
    }

    private static Double dblValue(Map<String, JangnyangSubtaskAnswer> a,
                                   JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtaskAnswer v = byField(a, def, field);
        return v != null && v.value() instanceof Number n ? n.doubleValue() : null;
    }

    private static List<?> listValue(Map<String, JangnyangSubtaskAnswer> a,
                                     JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtaskAnswer v = byField(a, def, field);
        return v != null && v.value() instanceof List<?> l ? l : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, JangnyangSubtaskAnswer> a,
                                                JangnyangSubtaskDefinition def, String field) {
        JangnyangSubtaskAnswer v = byField(a, def, field);
        return v != null && v.value() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /**
     * @param sufficient   실행 가능한 만큼 모였는가
     * @param scenarioType 판정 기준이 된 시나리오 유형({@code null}이면 아직 미선택)
     * @param missing      아직 필요한 서브태스크 ID
     * @param reasons      항목별 사유(카탈로그의 재질문 문장 포함)
     */
    public record CompletenessVerdict(boolean sufficient, String scenarioType,
                                      List<String> missing, List<SubtaskError> reasons) {}
}
