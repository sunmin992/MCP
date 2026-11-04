package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.wastesim.contract.*;
import com.wastesim.mcp.*;
import com.wastesim.template.*;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;
import java.util.*;

/** Assemble a single assumed block run from provider defaults, then require existing human approval. */
@Component
public class CreateNeighborhoodScenarioTool implements McpToolProvider {
    private final BuildScenarioTool build;
    private final TemplateCatalog catalog;
    private final SubtaskPlanner planner;
    private final ScenarioStore store;
    private final GetScenarioStatusTool status;
    private final ObjectMapper json;
    private final com.wastesim.region.OsmRegionResolver resolver;
    public CreateNeighborhoodScenarioTool(BuildScenarioTool build, TemplateCatalog catalog,
            SubtaskPlanner planner, ScenarioStore store, ObjectMapper json, GetScenarioStatusTool status) {
        this(build, catalog, planner, store, json, status, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CreateNeighborhoodScenarioTool(BuildScenarioTool build, TemplateCatalog catalog,
            SubtaskPlanner planner, ScenarioStore store, ObjectMapper json, GetScenarioStatusTool status,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.wastesim.region.OsmRegionResolver resolver) {
        this.build = build; this.catalog = catalog; this.planner = planner; this.store = store; this.json = json;
        this.status = status; this.resolver = resolver;
    }
    @Override public String toolName() { return "create_neighborhood_scenario"; }
    @Override public String description() {
        return "덕명동 등 임의 지역의 생활폐기물 수거 블록 시나리오를 구성한다. "
                + "요청의 지역명을 regionName에 그대로 전달한다. 아무 동네이면 생략할 수 있다. "
                + "공개 지도(OpenStreetMap)에서 그 지역을 찾아 실제 주거 블록(최대 26개 건물)의 건물 수·추정 세대 수·도로 이동시간으로 구성하고, "
                + "지도 자료를 못 구하면 공통 기본값의 가상 블록으로 구성한다. 같은 이름의 지역이 여러 곳이면 state=NEEDS_REGION과 "
                + "regionCandidates를 돌려주니 사용자에게 어느 곳인지 묻고 고른 regionName으로 다시 호출한다. 동 전체나 실측 민원을 예측하지 않는다. "
                + "요청한 값만 values 또는 requirements로 넘기고 나머지는 서버 템플릿 기본값으로 채운다. "
                + "브로커 selection을 그대로 전달한다. UNCONFIRMED 상태로 만들며 confirmUrl을 사용자에게 보여 주고 "
                + "확인 후 get_scenario_status → run_scenario_by_token으로 실행한다. 이 도구는 승인하거나 실행하지 않는다. "
                + "추가 구성 정보가 필요하면 missingInputs의 질문을 전달한다. 비교 실험은 기존 plan_subtasks/build_scenario를 사용한다.";
    }
    @Override public String inputSchemaJson() {
        return RequirementContract.toolSchema("""
            {"type":"object","properties":{
              "request":{"type":"string","minLength":1},
              "regionName":{"type":"string","minLength":1,"maxLength":120,"description":"사용자가 요청한 지역명. 미제시 시 가상 동네"},
              "values":{"type":"object","description":"사용자가 지정한 템플릿 answerKey 값만 전달"},
              "origins":{"type":"object"},"requirements":{"type":"object"},"selection":{"type":"object"},
              "requestId":{"type":"string"},"observations":{"type":"array","items":{"type":"string"}},
              "userUtterances":{"type":"array","items":{"type":"string"}},"evidence":{"type":"object"}},
             "required":["request"]}
            """);
    }
    @Override public ToolResult call(JsonNode args) {
        store.revise(args.path("requestId").asText(null), com.wastesim.readiness.RevisionInput.of(args));
        try {
            if (args.path("request").asText().isBlank()) throw new IllegalArgumentException("사용자 요청 원문이 필요합니다");
            var card = new com.wastesim.capability.CapabilityCardLoader().rawJson();
            var requirements = SelectionContract.requirements(args, card);
            // 사용자 표현(결과 · 수치 조건)을 이 시뮬레이터의 이름으로 대응시킨다. 원래 요구는 build_scenario 에 그대로 넘긴다.
            var spoken = RequirementTerms.resolve(requirements, card).requirements();
            String regionName = args.path("regionName").asText(requirements.path("regionId").asText("가상 동네"));
            var catalogRegions = new com.wastesim.region.RegionCatalog();
            String fallbackReason = null;
            boolean registered = catalogRegions.all().stream().anyMatch(r -> r.path("id").asText().equals(regionName));
            if (resolver != null && !registered && !regionName.equals("가상 동네")) {
                var resolved = resolver.resolve(regionName);
                switch (resolved.status()) {
                    case RESOLVED -> { }
                    case AMBIGUOUS -> {
                        var out = json.createObjectNode().put("valid", false).put("state", "NEEDS_REGION")
                                .put("message", resolved.reason());
                        out.set("regionCandidates", json.valueToTree(resolved.candidates()));
                        return ToolResult.ok(json.writeValueAsString(out));
                    }
                    default -> fallbackReason = resolved.reason();
                }
            }
            var region = catalogRegions.region(regionName);
            boolean mapped = region.path("basis").asText().equals("PUBLIC_MAP_DATA");
            if (requirements.has("regionId") && !regionName.equals(requirements.get("regionId").asText()))
                throw new IllegalArgumentException("선택 당시 지역과 regionName이 다릅니다. 다시 매칭하십시오");
            Map<String,Object> values = new LinkedHashMap<>();
            Map<String,String> origins = new LinkedHashMap<>();
            if (args.has("values") && !args.get("values").isObject()) throw new IllegalArgumentException("values는 객체여야 합니다");
            args.path("values").fields().forEachRemaining(e -> {
                if (catalog.byAnswerKey(e.getKey()).isEmpty()) throw new IllegalArgumentException("알 수 없는 입력: " + e.getKey());
                values.put(e.getKey(), JsonValues.plain(e.getValue()));
                origins.put(e.getKey(), args.path("origins").path(e.getKey()).asText("USER"));
            });
            spoken.path("constraints").fields().forEachRemaining(e -> {
                var template = catalog.all().stream().filter(t -> t.configField().equals(e.getKey())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("모델이 지원하지 않는 필수 조건: " + e.getKey()));
                putRequirement(values, origins, template.answerKey(), JsonValues.plain(e.getValue()));
            });
            if (requirements.has("simulationDuration")) {
                double days = RequirementContract.seconds(requirements.get("simulationDuration")) / 86400;
                if (days != Math.rint(days) || days < 1 || days > 365)
                    throw new IllegalArgumentException("현재 모델의 기간은 1~365일 정수입니다");
                putRequirement(values, origins, "days", (int) days);
            }
            Set<String> regionKeys = new HashSet<>();
            for (int pass = 0; pass <= catalog.all().size(); pass++) {
                boolean changed = false;
                for (var instance : planner.plan(values).instances()) {
                    if (instance.status() != SubtaskStatus.UNFILLED || values.containsKey(instance.answerKey())) continue;
                    String field = catalog.byAnswerKey(instance.answerKey()).orElseThrow().configField();
                    boolean fromRegion = region.path("defaults").has(field);
                    Object value = fromRegion ? JsonValues.plain(region.path("defaults").get(field))
                            : planner.defaultFor(instance.answerKey(), values);
                    // 가상 블록의 공통 값은 확인받을 기본값이고, 실제 지역 자료만 지역 출처로 넘긴다.
                    if (fromRegion && !region.path("synthetic").asBoolean()) regionKeys.add(instance.answerKey());
                    if (value == null) continue;
                    values.put(instance.answerKey(), value);
                    origins.put(instance.answerKey(), "MODEL_DEFAULT");
                    changed = true;
                }
                if (!changed) break;
            }
            var plan = planner.plan(values);
            if (!plan.complete()) {
                var out = json.createObjectNode().put("valid", false).put("state", "NEEDS_INPUT");
                out.set("missingInputs", json.valueToTree(plan.instances().stream().filter(i ->
                        i.status() == SubtaskStatus.UNFILLED || i.status() == SubtaskStatus.DEFERRED).toList()));
                out.set("region", region);
                return ToolResult.ok(json.writeValueAsString(out));
            }
            var input = json.createObjectNode();
            for (String key : List.of("request", "requestId", "selection", "userUtterances", "evidence"))
                if (args.has(key)) input.set(key, args.get(key));
            input.put("regionId", regionName);
            input.put("scenarioMode", mapped ? "PUBLIC_DATA_BLOCK" : "ASSUMED_BLOCK");
            input.set("requirements", requirements);
            input.put("variableAnswerKey", "days");
            input.putArray("variableValues").add(((Number) plan.answeredValues().get("days")).intValue());
            values.remove("days");
            // 지역 자료에서 온 값은 build_scenario 가 같은 지역 묶음에서 REGION_DATA 출처로 다시 채운다.
            regionKeys.forEach(k -> { values.remove(k); origins.remove(k); });
            input.set("values", json.valueToTree(values)); input.set("origins", json.valueToTree(origins));
            var observations = new LinkedHashSet<String>();
            args.path("observations").forEach(m -> observations.add(m.asText()));
            spoken.path("requiredOutputs").forEach(m -> observations.add(m.asText()));
            if (observations.isEmpty()) observations.add("meanComplaints");
            input.set("observations", json.valueToTree(observations));
            var purpose = input.putObject("purpose").put("mode", "RUN");
            purpose.set("observations", input.get("observations"));
            var result = build.call(input);
            if (!result.ready()) return result;
            var out = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(result.result().toString());
            out.put("scenarioMode", mapped ? "PUBLIC_DATA_BLOCK" : "ASSUMED_BLOCK");
            if (mapped) {
                // 건물별 상세는 캐시 묶음과 확인 화면에 남기고, LLM 에게 가는 응답에서는 동 수만 남긴다.
                for (JsonNode r : List.of(out.path("region"), out.path("validation").path("region")))
                    if (r.path("block").isObject()) {
                        var block = (com.fasterxml.jackson.databind.node.ObjectNode) r.get("block");
                        block.put("buildingCount", block.path("buildings").size());
                        block.remove("buildings");
                    }
                out.put("dataGrade", region.path("dataGrade").asText());
                out.put("dataNotice", "공개 지도에서 찾은 실제 주거 블록으로 구성했습니다. 건물 수·도로 이동은 지도 자료, 세대 수는 추정, 정차시간·배출 시간대는 가정값입니다.");
                out.set("provenance", region.path("provenance"));
                out.set("osm", region.path("osm"));
            } else {
                out.put("dataNotice", "공통 모델 기본값과 지정한 입력으로 구성한 가상 블록입니다. 실제 지역의 지리·인구·도로·교통을 재현한 결과가 아닙니다.");
                if (fallbackReason != null) out.put("fallbackReason", fallbackReason);
            }
            if (out.path("valid").asBoolean()) {
                var detail = status.call(json.createObjectNode().put("scenarioId", out.path("scenarioId").asText()));
                if (detail.ready()) {
                    var state = json.readTree(detail.result().toString());
                    out.set("confirmUrl", state.get("confirmUrl"));
                    out.set("nextStep", state.get("nextStep"));
                }
            }
            return ToolResult.ok(json.writeValueAsString(out));
        } catch (IllegalArgumentException e) { return ToolFailure.of("neighborhood", e.getMessage()); }
        catch (Exception e) { return ToolFailure.of("neighborhood", "동네 시나리오 생성 실패: " + e.getMessage()); }
    }
    private static void putRequirement(Map<String,Object> values, Map<String,String> origins, String key, Object value) {
        if (values.containsKey(key)) {
            Object old = values.get(key);
            boolean equal = old instanceof Number a && value instanceof Number b
                    ? Double.compare(a.doubleValue(), b.doubleValue()) == 0 : Objects.equals(old, value);
            if (!equal) throw new IllegalArgumentException("요구 조건과 입력값 불일치: " + key);
        } else {
            values.put(key, value); origins.put(key, "DERIVED");
        }
    }
}
