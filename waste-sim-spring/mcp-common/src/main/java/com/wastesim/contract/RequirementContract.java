package com.wastesim.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;

/** Versioned, deterministic checks. Missing declarations never imply support. */
public final class RequirementContract {
    private RequirementContract() {}
    /** Use the same machine-readable request shape in broker and all provider tool descriptions. */
    public static String toolSchema(String schema) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var out = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(schema);
            var requirements = json.readTree("""
                {"type":"object","additionalProperties":false,"properties":{
                  "schemaVersion":{"type":"string","pattern":"^1\\\\.\\\\d+\\\\.\\\\d+$"},
                  "regionId":{"type":"string","minLength":1},
                  "simulationDuration":{"type":"object","required":["value","unit"],"properties":{
                    "value":{"type":"number","exclusiveMinimum":0},
                    "unit":{"enum":["second","minute","hour","day","week"]}}},
                  "timeResolution":{"type":"object","required":["value","unit"],"properties":{
                    "value":{"type":"number","exclusiveMinimum":0},
                    "unit":{"enum":["second","minute","hour","day","week"]}}},
                  "requiredOutputs":{"type":"array","items":{"type":"string","minLength":1},"description":"사용자가 보고 싶다고 말한 결과를 사용자 말 그대로. 예: [\\"평균 민원 수\\"]. 시뮬레이터의 지표 이름을 짐작해 바꾸지 않는다"},
                  "constraints":{"description":"사용자가 반드시 지키라고 말한 수치 조건. 항목 이름과 단위는 사용자 말 그대로. 예: [{\\"name\\":\\"배차 간격\\",\\"value\\":1,\\"unit\\":\\"시간\\"}]","oneOf":[
                    {"type":"array","items":{"type":"object","required":["name","value"],"additionalProperties":false,"properties":{
                      "name":{"type":"string","minLength":1},"value":{"type":"number"},"unit":{"type":"string"}}}},
                    {"type":"object","additionalProperties":{"type":"number"}}]},
                  "preferences":{"description":"가능하면 지키면 좋은 수치 조건. constraints 와 같은 형식","oneOf":[
                    {"type":"array","items":{"type":"object","required":["name","value"],"additionalProperties":false,"properties":{
                      "name":{"type":"string","minLength":1},"value":{"type":"number"},"unit":{"type":"string"}}}},
                    {"type":"object","additionalProperties":{"type":"number"}}]},
                  "evidence":{"type":"object","additionalProperties":{"type":"string"},"description":"Original utterances indexed by requirement field"}
                }}
                """);
            var properties = (com.fasterxml.jackson.databind.node.ObjectNode) out.get("properties");
            properties.set("requirements", requirements);
            if (properties.has("selection")) {
                var selection = properties.putObject("selection");
                selection.put("type", "object");
                selection.putArray("required").add("schemaVersion").add("matchId").add("serverId").add("cardDigest").add("requirements");
                var p = selection.putObject("properties");
                for (String field : List.of("schemaVersion", "matchId", "serverId", "cardDigest"))
                    p.putObject(field).put("type", "string").put("minLength", 1);
                p.set("requirements", requirements);
            }
            return json.writeValueAsString(out);
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid tool schema", e); }
    }
    public record Check(String field, JsonNode requested, JsonNode declared, String status,
                        String code, String action, boolean required) {}
    private static final Set<String> FIELDS = Set.of("schemaVersion", "regionId", "simulationDuration",
            "timeResolution", "requiredOutputs", "constraints", "preferences", "evidence");
    public static JsonNode validate(JsonNode request) {
        if (request == null || request.isNull()) return JsonNodeFactory.instance.objectNode();
        if (!request.isObject()) throw new IllegalArgumentException("requirements must be an object");
        version(request);
        request.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k))
            throw new IllegalArgumentException("Unknown requirement: " + k); });
        if (request.has("regionId") && (!request.get("regionId").isTextual() || request.get("regionId").asText().isBlank()))
            throw new IllegalArgumentException("regionId must be nonempty text");
        for (String k : List.of("simulationDuration", "timeResolution")) if (request.has(k)) seconds(request.get(k));
        if (request.has("requiredOutputs")) strings(request.get("requiredOutputs"));
        for (String k : List.of("constraints", "preferences")) if (request.has(k)) {
            // 사용자 표현 [{name, value, unit}] 이 기본이고, 제공자 이름 {필드: 값} 은 옛 형식으로 받는다.
            if (request.get(k).isArray()) {
                for (JsonNode c : request.get(k)) {
                    if (!c.isObject() || !c.path("name").isTextual() || c.path("name").asText().isBlank()
                            || !c.path("value").isNumber() || !Double.isFinite(c.path("value").asDouble())
                            || c.has("unit") && !c.get("unit").isTextual())
                        throw new IllegalArgumentException(k + " entries need name (text), value (finite number), optional unit (text)");
                    c.fieldNames().forEachRemaining(f -> { if (!Set.of("name", "value", "unit").contains(f))
                        throw new IllegalArgumentException("Unknown " + k + " entry field: " + f); });
                }
                continue;
            }
            if (!request.get(k).isObject()) throw new IllegalArgumentException(k + " must be a list of {name, value, unit} or an object");
            request.get(k).fields().forEachRemaining(e -> {
                if (e.getKey().isBlank() || !e.getValue().isNumber() || !Double.isFinite(e.getValue().asDouble()))
                    throw new IllegalArgumentException(k + " values must be finite numbers");
            });
        }
        if (request.has("evidence")) {
            if (!request.get("evidence").isObject()) throw new IllegalArgumentException("evidence must map fields to original utterances");
            request.get("evidence").elements().forEachRemaining(v -> {
                if (!v.isTextual()) throw new IllegalArgumentException("evidence values must be original text");
            });
        }
        return request.deepCopy();
    }
    public static void version(JsonNode node) {
        if (node.has("schemaVersion") && (!node.get("schemaVersion").isTextual()
                || !node.get("schemaVersion").asText().matches("1\\.\\d+\\.\\d+")))
            throw new IllegalArgumentException("Unsupported schemaVersion (supported major: 1)");
    }
    /**
     * 등록하지 않은 지역명을 받는 방식. 둘 다 구성 전에는 지역 조건을 확정할 수 없어 CONDITIONAL 이다.
     * PUBLIC_MAP_BLOCK_FOR_ANY_LABEL 은 공개 지도에서 실제 블록을 찾아 보고, 못 찾으면 가상 블록으로 내린다.
     */
    public static final Set<String> REGION_POLICIES = Set.of("ASSUMED_BLOCK_FOR_ANY_LABEL", "PUBLIC_MAP_BLOCK_FOR_ANY_LABEL");
    public static void validateCard(JsonNode card) {
        version(card);
        JsonNode caps = card.path("capabilities");
        if (caps.isMissingNode()) return; // unversioned legacy cards may have no structured capabilities
        if (!caps.isObject()) throw new IllegalArgumentException("capabilities must be an object");
        if (caps.has("regions")) strings(caps.get("regions"));
        if (caps.has("regionPolicy") && !REGION_POLICIES.contains(caps.path("regionPolicy").path("mode").asText()))
            throw new IllegalArgumentException("Unsupported regionPolicy");
        if (caps.has("outputs")) strings(caps.get("outputs"));
        if (caps.has("conditionalOutputs")) strings(caps.get("conditionalOutputs"));
        if (caps.has("timeResolution")) seconds(caps.get("timeResolution"));
        if (caps.has("supportsAggregation") && !caps.get("supportsAggregation").isBoolean())
            throw new IllegalArgumentException("supportsAggregation must be boolean");
        if (caps.has("simulationDuration")) {
            var range = caps.get("simulationDuration");
            if (seconds(range.path("min")) > seconds(range.path("max")))
                throw new IllegalArgumentException("Invalid duration range");
        }
        if (caps.has("constraints")) {
            if (!caps.get("constraints").isObject()) throw new IllegalArgumentException("constraints must be an object");
            caps.get("constraints").fields().forEachRemaining(e -> {
                var r = e.getValue();
                if (!r.path("min").isNumber() || !r.path("max").isNumber()
                        || !Double.isFinite(r.path("min").asDouble()) || !Double.isFinite(r.path("max").asDouble())
                        || r.path("min").asDouble() > r.path("max").asDouble())
                    throw new IllegalArgumentException("Invalid constraint range: " + e.getKey());
            });
        }
        RequirementTerms.validate(caps);
    }
    public static double seconds(JsonNode q) {
        if (!q.isObject() || !q.path("value").isNumber()) throw new IllegalArgumentException("Time requires value and unit");
        double factor = switch (q.path("unit").asText()) {
            case "second" -> 1; case "minute" -> 60; case "hour" -> 3600;
            case "day" -> 86400; case "week" -> 604800;
            default -> throw new IllegalArgumentException("Unsupported time unit");
        };
        double n = q.get("value").asDouble() * factor;
        if (!Double.isFinite(n) || n <= 0) throw new IllegalArgumentException("Time must be finite and positive");
        return n;
    }
    private static List<String> strings(JsonNode a) {
        if (!a.isArray()) throw new IllegalArgumentException("Expected string array");
        List<String> out = new ArrayList<>();
        for (JsonNode v : a) {
            if (!v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException("Expected nonempty text");
            out.add(v.asText());
        }
        return out;
    }
    public static List<Check> evaluate(JsonNode request, JsonNode card) {
        validateCard(card);
        // 사용자 표현을 이 카드의 이름으로 대응시킨다. 대응되지 않은 표현은 판정 결과로 남는다.
        var resolution = RequirementTerms.resolve(request, card);
        request = resolution.requirements();
        List<Check> out = new ArrayList<>(resolution.issues());
        JsonNode caps = card.path("capabilities");
        if (request.has("regionId")) {
            String id = request.get("regionId").asText();
            JsonNode regions = caps.path("regions");
            boolean assumed = REGION_POLICIES.contains(caps.path("regionPolicy").path("mode").asText());
            add(out, "regionId", request.get("regionId"), regions,
                    regions.isArray() && strings(regions).contains(id) ? "SATISFIED" : assumed ? "CONDITIONAL"
                            : regions.isMissingNode() ? "UNKNOWN" : "UNSATISFIED", true);
        }
        if (request.has("timeResolution")) {
            JsonNode declared = caps.path("timeResolution");
            String status = "UNKNOWN";
            if (!declared.isMissingNode()) {
                double need = seconds(request.get("timeResolution")), step = seconds(declared);
                // No implicit aggregation: only an explicitly declared aggregation capability permits it.
                status = Math.abs(need - step) < 1e-6 ? "SATISFIED"
                        : need > step && caps.path("supportsAggregation").asBoolean(false)
                        && Math.abs(need / step - Math.rint(need / step)) < 1e-9 ? "SATISFIED" : "UNSATISFIED";
            }
            add(out, "timeResolution", request.get("timeResolution"), declared, status, true);
        }
        if (request.has("simulationDuration")) {
            JsonNode range = caps.path("simulationDuration");
            String status = "UNKNOWN";
            if (range.has("min") && range.has("max")) {
                double n = seconds(request.get("simulationDuration"));
                status = n >= seconds(range.get("min")) && n <= seconds(range.get("max")) ? "SATISFIED" : "UNSATISFIED";
            }
            add(out, "simulationDuration", request.get("simulationDuration"), range, status, true);
        }
        if (request.has("requiredOutputs")) for (String metric : strings(request.get("requiredOutputs"))) {
            JsonNode metrics = caps.path("outputs");
            boolean conditional = caps.has("conditionalOutputs") && strings(caps.get("conditionalOutputs")).contains(metric);
            add(out, "requiredOutputs." + metric, JsonNodeFactory.instance.textNode(metric), metrics,
                    metrics.isMissingNode() ? "UNKNOWN" : !strings(metrics).contains(metric) ? "UNSATISFIED"
                            : conditional ? "CONDITIONAL" : "SATISFIED", true);
        }
        for (String group : List.of("constraints", "preferences")) {
            final boolean required = group.equals("constraints");
            request.path(group).fields().forEachRemaining(e -> {
                JsonNode range = caps.path("constraints").path(e.getKey());
                String status = !range.has("min") || !range.has("max") ? "UNKNOWN"
                        : e.getValue().asDouble() >= range.path("min").asDouble()
                        && e.getValue().asDouble() <= range.path("max").asDouble() ? "SATISFIED" : "UNSATISFIED";
                add(out, group + "." + e.getKey(), e.getValue(), range, status, required);
            });
        }
        return List.copyOf(out);
    }
    private static void add(List<Check> out, String field, JsonNode need, JsonNode declared, String status, boolean required) {
        out.add(new Check(field, need, declared, status,
                status.equals("SATISFIED") ? "SUPPORTED" : status.equals("CONDITIONAL") ? "CONFIGURATION_CHECK_REQUIRED"
                        : status.equals("UNKNOWN") ? "CAPABILITY_UNDECLARED" : "CAPABILITY_MISMATCH",
                status.equals("SATISFIED") ? "NONE" : status.equals("CONDITIONAL") ? "VERIFY_GENERATED_CONFIGURATION"
                        : status.equals("UNKNOWN") ? "PROVIDER_DECLARATION_REQUIRED" : "CHANGE_REQUEST_OR_PROVIDER", required));
    }
}
