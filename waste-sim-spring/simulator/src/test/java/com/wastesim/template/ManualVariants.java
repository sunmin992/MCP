package com.wastesim.template;

import com.wastesim.model.SimulationConfig;
import java.util.*;
import java.util.function.Predicate;

/**
 * R3 대조군의 변경 사례. 각 변형은 {@link ManualConfigurator} 에서 고친 구간만 다시 쓴다.
 * primary = 규칙 자체만 고친 경우, complete = 일관성을 위해 함께 고쳐야 하는 곳까지 고친 경우.
 * 실험은 {@code // <variant:…>} 블록 안의 {@code site} 구간을 기준 클래스의 같은 구간과 비교한다.
 */
final class ManualVariants {
    private ManualVariants() { }

    static ManualConfigurator of(String id) {
        return switch (id) {
            case "C1-primary" -> new C1Primary();
            case "C1-complete" -> new C1Complete();
            case "C2-primary" -> new C2Primary();
            case "C2-complete" -> new C2Complete();
            case "C3-primary", "C3-complete" -> new C3();
            case "C4-primary" -> new C4Primary();
            case "C4-complete" -> new C4Complete();
            default -> throw new IllegalArgumentException(id);
        };
    }

    // <variant:C1-primary>
    static class C1Primary extends ManualConfigurator {
        // <site:dispatchActive>
        @Override public boolean dispatchActive(Map<String, Object> v) {
            return Math.min(num(v, "numBuildings"), num(v, "truckCount")) >= 3;
        }
        // </site>
    }
    // </variant>

    // <variant:C1-complete>
    static class C1Complete extends C1Primary {
        // <site:card>
        @Override public Map<String, Map<String, Object>> card() {
            Map<String, Map<String, Object>> c = new LinkedHashMap<>();
            c.put("dispatchIntervalMinutes", Map.of("generateWhen", "min(numBuildings, numTrucks) >= 3", "max", 1439));
            c.put("trafficProfileId", Map.of("generateWhen", "trafficEnabled == true"));
            return c;
        }
        // </site>
    }
    // </variant>

    // <variant:C2-primary>
    static class C2Primary extends ManualConfigurator {
        // <site:serviceActive>
        public boolean serviceActive(Map<String, Object> v) {
            return "ZONE_PROXY_HYBRID".equals(v.get("travelTimeMode"));
        }
        // </site>
        // <site:decisions>
        @Override public Map<String, Predicate<Map<String, Object>>> decisions() {
            Map<String, Predicate<Map<String, Object>>> d = new LinkedHashMap<>();
            d.put("dispatchIntervalMinutes", this::dispatchActive);
            d.put("trafficProfileId", this::trafficProfileActive);
            d.put("zoneAssignmentRule", this::zoneActive);
            d.put("intraZoneTravelMinutes", this::intraActive);
            d.put("serviceMinutesPerSite", this::serviceActive);
            return d;
        }
        // </site>
    }
    // </variant>

    // <variant:C2-complete>
    static class C2Complete extends C2Primary {
        // <site:ranges>
        @Override public Map<String, int[]> ranges() {
            Map<String, int[]> r = new LinkedHashMap<>();
            r.put("dispatchIntervalMinutes", new int[]{0, 1439});
            r.put("intraZoneTravelMinutes", new int[]{0, Integer.MAX_VALUE});
            r.put("serviceMinutesPerSite", new int[]{0, 60});
            return r;
        }
        // </site>
        // <site:questions>
        @Override public Map<String, String> questions() {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("dispatchIntervalMinutes", "차량 간 배차 간격을 몇 분으로 할까요?");
            q.put("trafficProfileId", "어떤 교통 프로파일을 쓸까요? (평일 실측 / 통행량 기반)");
            q.put("zoneAssignmentRule", "건물을 교통 구역에 어떻게 배정할까요? (덩어리 / 번갈아)");
            q.put("intraZoneTravelMinutes", "같은 교통 구역 안의 이동시간을 몇 분으로 볼까요?");
            q.put("serviceMinutesPerSite", "수거 지점마다 정차·상차에 몇 분이 걸리나요?");
            return q;
        }
        // </site>
        // <site:card>
        @Override public Map<String, Map<String, Object>> card() {
            Map<String, Map<String, Object>> c = new LinkedHashMap<>();
            c.put("dispatchIntervalMinutes", Map.of("generateWhen", "min(numBuildings, numTrucks) >= 2", "max", 1439));
            c.put("trafficProfileId", Map.of("generateWhen", "trafficEnabled == true"));
            c.put("serviceMinutesPerSite", Map.of("generateWhen", "travelTimeMode == ZONE_PROXY_HYBRID", "max", 60));
            return c;
        }
        // </site>
        // <site:taskGroups>
        @Override public Map<String, List<String>> taskGroups() {
            return Map.of("vehicle", List.of("dispatchIntervalMinutes"),
                    "travel", List.of("trafficProfileId", "zoneAssignmentRule", "intraZoneTravelMinutes", "serviceMinutesPerSite"));
        }
        // </site>
        // <site:flatten>
        @Override public void flatten(SimulationConfig cfg, Map<String, Object> v) {
            super.flatten(cfg, v);
            if (v.containsKey("serviceMinutesPerSite")) cfg.setServiceMinutesPerSite(num(v, "serviceMinutesPerSite"));
        }
        // </site>
    }
    // </variant>

    // <variant:C3>
    static class C3 extends ManualConfigurator {
        // <site:zoneActive>
        @Override public boolean zoneActive(Map<String, Object> v) {
            return "ZONE_PROXY_HYBRID".equals(v.get("travelTimeMode"));
        }
        // </site>
    }
    // </variant>

    // <variant:C4-primary>
    static class C4Primary extends ManualConfigurator {
        // <site:ranges>
        @Override public Map<String, int[]> ranges() {
            Map<String, int[]> r = new LinkedHashMap<>();
            r.put("dispatchIntervalMinutes", new int[]{0, 120});
            r.put("intraZoneTravelMinutes", new int[]{0, Integer.MAX_VALUE});
            return r;
        }
        // </site>
    }
    // </variant>

    // <variant:C4-complete>
    static class C4Complete extends C4Primary {
        // <site:card>
        @Override public Map<String, Map<String, Object>> card() {
            Map<String, Map<String, Object>> c = new LinkedHashMap<>();
            c.put("dispatchIntervalMinutes", Map.of("generateWhen", "min(numBuildings, numTrucks) >= 2", "max", 120));
            c.put("trafficProfileId", Map.of("generateWhen", "trafficEnabled == true"));
            return c;
        }
        // </site>
    }
    // </variant>
}
