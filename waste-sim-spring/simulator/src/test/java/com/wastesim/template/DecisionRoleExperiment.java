package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

/**
 * 결정의 역할(INTENT · DATA · ASSUMPTION)로 세 처리 방식을 비교한다. 기본 *Test 실행에서 빠진다.
 * <pre>.\mvnw.cmd -B -pl simulator -am test '-Dtest=DecisionRoleExperiment' '-Dsurefire.failIfNoSpecifiedTests=false'</pre>
 *
 * <p>요청마다 사용자가 말한 결정을 미리 정해 두고, 실제 생성 조건(SubtaskPlanner)으로 그 요청에 필요한 결정 전체를 구한다.
 * 그 위에서 방식마다 사용자 부담과 위험을 센다. 사람 대상 측정이 아니라, 같은 결정 목록에 처리 규칙만 바꿔 본 구조 비교다.
 * <ul>
 *   <li>ASK_ALL — 말하지 않은 필요 결정을 모두 묻는다.</li>
 *   <li>LLM_FILLS — 묻지 않고 모두 채운다(LLM 이 알아서 채우는 경우). 무엇을 채웠는지 구분하지 않는다.</li>
 *   <li>ROLE_CONFIRM — 지금 구현. 데이터 결정은 지역 자료로 채우고, 의도 결정은 기본값을 넣되 "당신이 정할 항목"으로
 *       확인 화면 맨 위에 따로 보이고, 가정은 가정으로 표시한다.</li>
 *   <li>ROLE_ASK — 의도 결정만 묻고 나머지는 ROLE_CONFIRM 과 같다.</li>
 * </ul>
 */
class DecisionRoleExperiment {
    record Request(String id, String utterance, Map<String, Object> said) {}

    static List<Request> requests() {
        return List.of(
            new Request("Q01", "상계동에서 이틀 동안 돌려서 민원 수 보여줘", Map.of("days", 2)),
            new Request("Q02", "차량 2대를 10분 간격으로 내보내고 이틀 동안 봐줘", Map.of("truckCount", 2, "dispatchIntervalMinutes", 10, "days", 2)),
            new Request("Q03", "오후 6시에 수거하면 어떻게 돼?", Map.of("collectionTimeMinutes", 1080)),
            new Request("Q04", "교통을 반영해서 정오에 수거해 봐", Map.of("trafficMode", "APPLY", "collectionTimeMinutes", 720)),
            new Request("Q05", "5톤 차량 1대로 한 달 돌려줘", Map.of("truckType", "LARGE_5TON", "truckCount", 1, "days", 30)),
            new Request("Q06", "구역 근사 이동으로 건물 8동을 해줘", Map.of("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 8)),
            new Request("Q07", "적재율 70% 넘으면 민원으로 보고 돌려줘", Map.of("complaintThreshold", 0.7)),
            new Request("Q08", "정확하게 10회 반복해줘", Map.of("seeds", 10)),
            new Request("Q09", "학생이 많은 동네로 해줘", Map.of("occupationMix", List.of("Student", "Student", "BlueCollar"))),
            new Request("Q10", "아무 동네나 돌려줘", Map.of()),
            new Request("Q11", "하루 두 번, 11시와 23시에 수거해줘", Map.of("collectionTimesMinutes", List.of(660, 1380))),
            new Request("Q12", "차량 3대로 교통 반영하고 평일 실측 교통으로 해줘",
                    Map.of("truckCount", 3, "trafficMode", "APPLY", "trafficProfileId", "jangryang-weekday")));
    }

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog, new AnswerNormalizer());

    /** 말한 값에서 시작해 필요한 결정을 기본값(없으면 첫 선택지 · 최솟값)으로 채워 가며, 이 요청에 필요한 결정 전체를 구한다. */
    List<String> required(Map<String, Object> said) {
        Map<String, Object> values = new LinkedHashMap<>(said);
        for (int pass = 0; pass < 40; pass++) {
            boolean changed = false;
            for (var i : planner.plan(values).instances()) {
                if (i.status() == SubtaskStatus.NOT_GENERATED || values.containsKey(i.answerKey())) continue;
                if (i.status() != SubtaskStatus.UNFILLED) continue;
                var t = catalog.byAnswerKey(i.answerKey()).orElseThrow();
                Object v = planner.defaultFor(i.answerKey(), values);
                if (v == null) v = !t.allowed().isEmpty() ? t.allowed().get(0) : t.min() != null ? (Object) t.min().intValue() : 1;
                values.put(i.answerKey(), v);
                changed = true;
            }
            if (!changed) break;
        }
        return planner.plan(values).instances().stream().filter(i -> i.status() != SubtaskStatus.NOT_GENERATED)
                .map(SubtaskInstance::answerKey).toList();
    }

    String role(String key) { return catalog.byAnswerKey(key).orElseThrow().decisionRole(); }

    /** 지역 자료가 있으면 이 데이터 결정은 자료로 채워진다(공개 지도 블록). 경로 적재 몫은 수집 출처가 없다. */
    static final Set<String> REGION_FILLS = Set.of("numBuildings", "residentsPerBuilding", "binCapacityKg");

    Map<String, Object> measure(String approach, Request r, boolean regionData) {
        int asked = 0, confirmed = 0, silentIntent = 0, dataWithoutData = 0, unlabeledAssumptions = 0;
        List<String> silent = new ArrayList<>();
        for (String key : required(r.said())) {
            if (r.said().containsKey(key)) continue;
            String role = role(key);
            boolean fromData = role.equals("DATA") && regionData && REGION_FILLS.contains(key);
            switch (approach) {
                case "ASK_ALL" -> asked++;
                case "LLM_FILLS" -> {
                    if (role.equals("INTENT")) { silentIntent++; silent.add(key); }
                    if (role.equals("DATA") && !fromData) dataWithoutData++;
                    if (role.equals("ASSUMPTION")) unlabeledAssumptions++;
                }
                case "ROLE_CONFIRM", "ROLE_ASK" -> {
                    if (role.equals("INTENT")) { if (approach.equals("ROLE_ASK")) asked++; else confirmed++; }
                    if (role.equals("DATA") && !fromData) confirmed++;   // dataGaps 로 따로 보인다
                }
                default -> throw new IllegalArgumentException(approach);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asked", asked); m.put("confirmItems", confirmed); m.put("silentIntent", silentIntent);
        m.put("dataWithoutData", dataWithoutData); m.put("unlabeledAssumptions", unlabeledAssumptions);
        if (!silent.isEmpty()) m.put("silentIntentKeys", silent);
        return m;
    }

    @Test void compareApproaches() throws Exception {
        List<String> approaches = List.of("ASK_ALL", "LLM_FILLS", "ROLE_CONFIRM", "ROLE_ASK");
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Long> roles = new TreeMap<>();
        catalog.all().forEach(t -> roles.merge(t.decisionRole(), 1L, Long::sum));
        report.put("decisionRoles", roles);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Integer>> totals = new LinkedHashMap<>();
        for (boolean regionData : List.of(true, false)) for (Request r : requests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id()); row.put("utterance", r.utterance()); row.put("regionData", regionData);
            row.put("said", r.said().keySet());
            List<String> req = required(r.said());
            row.put("requiredDecisions", req.size());
            Map<String, Long> unsaid = new TreeMap<>();
            req.stream().filter(k -> !r.said().containsKey(k)).forEach(k -> unsaid.merge(role(k), 1L, Long::sum));
            row.put("unsaidByRole", unsaid);
            for (String a : approaches) {
                var m = measure(a, r, regionData);
                row.put(a, m);
                var t = totals.computeIfAbsent(a + (regionData ? "/지역자료있음" : "/지역자료없음"), k -> new LinkedHashMap<>());
                m.forEach((k, v) -> { if (v instanceof Integer n) t.merge(k, n, Integer::sum); });
            }
            rows.add(row);
        }
        report.put("requests", requests().size());
        report.put("totals", totals);
        report.put("rows", rows);
        Path out = Path.of("../docs/reports/decision-roles-2026-11-04");
        Files.createDirectories(out);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("results.json").toFile(), report);
        System.out.println(new ObjectMapper().writeValueAsString(Map.of("roles", roles, "totals", totals)));
    }
}
