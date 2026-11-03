package com.wastesim.template;

import com.wastesim.model.SimulationConfig;
import java.util.*;
import java.util.function.Predicate;

/**
 * R3 대조군: SES 없이 같은 조건부 결정 규칙을 Java 로 직접 짠 구성기.
 *
 * <p>일부러 엉성하게 짜지 않는다 — 결정마다 공용 판정 함수를 두고 준비 판정 · 질문 · 잘못 남은 입력 거절 ·
 * 구조 표시 · 능력 카드 · 설정 반영 · Task 묶음이 모두 같은 함수와 표를 쓴다. 다룬 범위는 SES 의 조건부 결정
 * 네 개다(배차 간격, 교통 프로파일, 구역 배정, 구역 내 이동). 항상 생성되는 결정은 두 방식에서 차이가 없어 뺐다.
 *
 * <p>{@code // <site:이름>} 과 {@code // </site>} 사이가 고치는 단위다. 변경 사례는 이 클래스를 상속해
 * 해당 구간만 다시 쓴다. 실험은 구간 원문을 비교해 바뀐 줄 수를 센다.
 */
public class ManualConfigurator {
    static int num(Map<String, Object> v, String k) { return ((Number) v.get(k)).intValue(); }

    // <site:dispatchActive>
    public boolean dispatchActive(Map<String, Object> v) {
        return Math.min(num(v, "numBuildings"), num(v, "truckCount")) >= 2;
    }
    // </site>

    // <site:trafficProfileActive>
    public boolean trafficProfileActive(Map<String, Object> v) {
        return "APPLY".equals(v.get("trafficMode"));
    }
    // </site>

    // <site:zoneActive>
    public boolean zoneActive(Map<String, Object> v) {
        return "ZONE_PROXY_HYBRID".equals(v.get("travelTimeMode")) && num(v, "numBuildings") > 4;
    }
    // </site>

    // <site:intraActive>
    public boolean intraActive(Map<String, Object> v) {
        return zoneActive(v) && "CONTIGUOUS".equals(v.get("zoneAssignmentRule"));
    }
    // </site>

    // <site:decisions>
    public Map<String, Predicate<Map<String, Object>>> decisions() {
        Map<String, Predicate<Map<String, Object>>> d = new LinkedHashMap<>();
        d.put("dispatchIntervalMinutes", this::dispatchActive);
        d.put("trafficProfileId", this::trafficProfileActive);
        d.put("zoneAssignmentRule", this::zoneActive);
        d.put("intraZoneTravelMinutes", this::intraActive);
        return d;
    }
    // </site>

    /** intraZoneTravelMinutes 는 구역 배정이 정해져야 판정할 수 있다. 판정 근거가 되는 결정. */
    // <site:dependsOn>
    public Map<String, String> dependsOn() {
        return Map.of("intraZoneTravelMinutes", "zoneAssignmentRule");
    }
    // </site>

    // <site:ranges>
    public Map<String, int[]> ranges() {
        Map<String, int[]> r = new LinkedHashMap<>();
        r.put("dispatchIntervalMinutes", new int[]{0, 1439});
        r.put("intraZoneTravelMinutes", new int[]{0, Integer.MAX_VALUE});
        return r;
    }
    // </site>

    // <site:allowed>
    public Map<String, List<String>> allowed() {
        return Map.of("trafficProfileId", List.of("jangryang-weekday", "jangryang-volume-weekday"),
                "zoneAssignmentRule", List.of("CONTIGUOUS", "ROUND_ROBIN"));
    }
    // </site>

    // <site:questions>
    public Map<String, String> questions() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("dispatchIntervalMinutes", "차량 간 배차 간격을 몇 분으로 할까요?");
        q.put("trafficProfileId", "어떤 교통 프로파일을 쓸까요? (평일 실측 / 통행량 기반)");
        q.put("zoneAssignmentRule", "건물을 교통 구역에 어떻게 배정할까요? (덩어리 / 번갈아)");
        q.put("intraZoneTravelMinutes", "같은 교통 구역 안의 이동시간을 몇 분으로 볼까요?");
        return q;
    }
    // </site>

    /** 브로커 · LLM 이 읽는 능력 카드 선언. SES 카드와 같은 내용을 같은 범위로 적는다. */
    // <site:card>
    public Map<String, Map<String, Object>> card() {
        Map<String, Map<String, Object>> c = new LinkedHashMap<>();
        c.put("dispatchIntervalMinutes", Map.of("generateWhen", "numBuildings >= 2 && truckCount >= 2", "max", 1439));
        c.put("trafficProfileId", Map.of("generateWhen", "trafficMode == APPLY"));
        return c;
    }
    // </site>

    // <site:taskGroups>
    public Map<String, List<String>> taskGroups() {
        return Map.of("vehicle", List.of("dispatchIntervalMinutes"),
                "travel", List.of("trafficProfileId", "zoneAssignmentRule", "intraZoneTravelMinutes"));
    }
    // </site>

    // <site:flatten>
    public void flatten(SimulationConfig cfg, Map<String, Object> v) {
        if (v.containsKey("dispatchIntervalMinutes")) cfg.setDispatchIntervalMinutes(num(v, "dispatchIntervalMinutes"));
        if (v.containsKey("trafficProfileId")) cfg.setTrafficProfileId((String) v.get("trafficProfileId"));
        if (v.containsKey("zoneAssignmentRule")) cfg.setZoneAssignmentRule((String) v.get("zoneAssignmentRule"));
        if (v.containsKey("intraZoneTravelMinutes")) cfg.setIntraZoneTravelMinutes(num(v, "intraZoneTravelMinutes"));
    }
    // </site>

    // ── 소비자: 위 표만 읽는다. 변경 사례는 여기를 고치지 않는다. ──

    /** 판정할 수 없으면(근거 결정이 활성인데 값이 없으면) null. */
    public final Boolean active(String key, Map<String, Object> v) {
        String dep = dependsOn().get(key);
        if (dep != null && decisions().get(dep).test(v) && !v.containsKey(dep)) return null;
        return decisions().get(key).test(v);
    }
    public final boolean outOfRange(String key, Object value) {
        int[] r = ranges().get(key);
        if (r != null) return ((Number) value).intValue() < r[0] || ((Number) value).intValue() > r[1];
        List<String> a = allowed().get(key);
        return a != null && !a.contains(value);
    }
}
