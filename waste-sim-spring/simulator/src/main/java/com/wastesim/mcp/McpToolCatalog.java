package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.tool.SimulationTool;
import org.springframework.stereotype.Component;

/**
 * MCP 도구 카탈로그 — tools/list 로 노출되는 도구 정의와 JSON Schema.
 *  - {@link SimulationModelRegistry}에 등록된 모델마다 하나씩(예:
 *    run_waste_simulation=Java 엔진, run_waste_simulation_devs=Python 엔진) —
 *    새 모델이 추가돼도 이 클래스는 손댈 필요가 없다(MCP_모델_연결_방법.md §2).
 *  - run_scenario         : 복잡한 시나리오 실험(구성·sweep·그리드·트럭·분리배출 등)
 *  - list_scenarios       : 지원 시나리오 유형·프리셋 조회
 */
@Component
public class McpToolCatalog {

    private final SimulationTool tool;
    private final SimulationModelRegistry models;
    private final McpToolRegistry independentTools;

    public McpToolCatalog(SimulationTool tool, SimulationModelRegistry models, McpToolRegistry independentTools) {
        this.tool = tool;
        this.models = models;
        this.independentTools = independentTools;
    }

    /** package-private이 아니라 public — {@link SimulationModelProvider} 구현체들이
     *  모델 고유 스키마가 필요 없을 때(현재 전부) 그대로 재사용한다. */
    public static final String RUN_SIM_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "collectionTime": {"type": "string", "description": "수거 시각 HH:MM (예: 12:00)"},
                "days": {"type": "integer", "description": "시뮬레이션 기간(일)", "default": 30},
                "seeds": {"type": "integer", "description": "반복 횟수", "default": 30},
                "leaveSigma": {"type": "number", "description": "외출 시각 표준편차(분)", "default": 30.0},
                "wasteSigma": {"type": "number", "description": "일일 배출량 표준편차(kg)", "default": 0.3},
                "wasteMeanKg": {"type": "number",
                  "description": "1인 1일 평균 배출량(kg). 기본값 0.9는 논문 가정치. 환경부 전국폐기물통계조사 실측 평균(약 0.95kg, 지역 편차 0.8~1.7kg)으로 캘리브레이션할 때 사용", "default": 0.9},
                "capacity": {"type": "number", "description": "수거장 용량(kg)", "default": 30.0},
                "threshold": {"type": "number", "description": "민원 임계 적재율(0~1)", "default": 0.8},
                "numBuildings": {"type": "integer", "default": 4},
                "residentsPerBuilding": {"type": "integer", "default": 25},
                "collectionIntervalDays": {"type": "integer", "minimum": 1, "default": 1,
                  "description": "수거 주기(일). 2면 격일 수거 — 차량은 수거일에만 운행하므로 비수거일에는 이동시간·교통 민원이 발생하지 않는다"},
                "collectionDaysOfWeek": {"type": "array", "items": {"type": "integer"},
                  "description": "요일 기반 수거일. 0=월~6=일. 지정하면 collectionIntervalDays 대신 사용"},
                "dischargeTimeMode": {"type": "string", "enum": ["PAPER_BASELINE","POHANG_ACTUAL"],
                  "default": "PAPER_BASELINE", "description": "배출 시각 모델: 논문 기준선 또는 포항 공식 배출 창 기반"},
                "dischargeWindowStartMinutes": {"type": "integer", "minimum": 0, "maximum": 1439,
                  "default": 1200, "description": "POHANG_ACTUAL 배출 창 시작(자정부터 분, 20:00=1200)"},
                "dischargeWindowEndMinutes": {"type": "integer", "minimum": 0, "maximum": 1439,
                  "default": 360, "description": "POHANG_ACTUAL 배출 창 종료(자정부터 분, 06:00=360)"},
                "occupationMix": {"type": "array", "items": {"type": "string"},
                  "description": "직업 구성: BlueCollar/Student/Housewife/NightShift/OfficeWorker"},
                "trafficEnabled": {"type": "boolean", "description": "교통 레이어 사용 여부", "default": false},
                "trafficProfileId": {"type": "string", "description": "예: jangryang-weekday"},
                "routeTravelMinutes": {"type": "integer",
                  "description": "건물 간 기본 이동시간(분, 혼잡 가중치 적용 전). run_waste_simulation_devs(Python 엔진)도 동일 필드 사용", "default": 8},
                "travelTimeMode": {"type": "string",
                  "enum": ["LEGACY_CONSTANT","OSRM_HYBRID","ZONE_PROXY_HYBRID"],
                  "default": "LEGACY_CONSTANT", "description": "이동시간 계산 방식"},
                "serviceMinutesPerSite": {"type": "integer", "minimum": 0, "default": 0,
                  "description": "혼합 모드의 수거 지점별 정차·상차 시간(분, 첫 지점 포함)"},
                "zoneAssignmentRule": {"type": "string",
                  "enum": ["NONE","CONTIGUOUS","ROUND_ROBIN"], "default": "NONE",
                  "description": "건물을 교통 구역 A~D에 배정하는 **가정**. 5동 이상을 ZONE_PROXY_HYBRID로 돌리려면 필요하다(배정이 없으면 지점 id를 구역 id로 보는 폴백이 4동까지만 통한다). 실제 위치 조사가 아니므로 결과가 운영 예측이 아닌 가정 비교 실험으로 표시된다 — CONTIGUOUS와 ROUND_ROBIN을 함께 돌려 범위로 보고할 것"},
                "intraZoneTravelMinutes": {"type": "integer", "minimum": 0,
                  "description": "ZONE_PROXY_HYBRID에서 같은 교통 구역 안의 지점 간 이동시간. 필요한 경로에서는 반드시 명시"},
                "truckType": {"type": "string", "enum": ["LARGE_5TON","MEDIUM_2P5T","SMALL_1TON"], "default": "LARGE_5TON"},
                "routeAvailableCapacityKg": {"type": "number", "exclusiveMinimum": 0,
                  "description": "운행 1회당 해당 경로에 배정된 적재용량(kg). 미입력 시 차종 정격용량"},
                "initialTruckLoadKg": {"type": "number", "minimum": 0, "default": 0,
                  "description": "운행 시작 시 이미 적재된 양(kg). 신규 수거 가능량에서 차감"},
                "truckCount": {"type": "integer", "description": "투입 트럭 대수", "default": 1, "minimum": 1},
                "dispatchIntervalMinutes": {"type": "integer", "description": "트럭 간 시차 배차(분)", "default": 0},
                "routeSequence": {"type": "array", "items": {"type": "string"},
                  "description": "수거장 방문 순서(노드 id). 예: [\\"Node_A\\",\\"Node_C\\",\\"Node_B\\"]"}
              },
              "required": ["collectionTime"]
            }
            """;

    private static final String UPDATE_ROUTE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "routeSequence": {"type": "array", "items": {"type": "string"},
                  "description": "수거장 방문 순서. 예: [\\"Node_A\\",\\"Node_C\\",\\"Node_B\\"]"},
                "collectionTime": {"type": "string"},
                "trafficProfileId": {"type": "string"}
              },
              "required": ["routeSequence"]
            }
            """;

    private static final String RUN_SCENARIO_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "type": {"type": "string",
                  "enum": ["occupation-mix","collection-sweep","behavior-grid","infra-grid","density",
                           "collection-schedule","multi-truck","waste-separation","new-occupations",
                           "coupling-variants","monthly-waste","truck-route"],
                  "description": "실행할 시나리오 실험 유형"},
                "days": {"type": "integer", "default": 30},
                "seeds": {"type": "integer", "default": 10},
                "capacity": {"type": "number", "default": 30.0},
                "threshold": {"type": "number", "default": 0.8},
                "leaveSigma": {"type": "number", "default": 30.0},
                "wasteSigma": {"type": "number", "default": 0.3},
                "wasteMeanKg": {"type": "number", "default": 0.9,
                  "description": "1인 1일 평균 배출량(kg). 실측 캘리브레이션 시 조정"},
                "numBuildings": {"type": "integer", "default": 4},
                "residentsPerBuilding": {"type": "integer", "default": 25},
                "occupationMix": {"type": "array", "items": {"type": "string"}}
              },
              "required": ["type"]
            }
            """;

    private static final String EMPTY_SCHEMA = """
            {"type": "object", "properties": {}}
            """;

    /**
     * tools/list 결과 노드 {@code { "tools": [ ... ] }}.
     *
     * <p>도메인 필터가 없다 — 이 서버는 장량동 하나만 다루므로 걸러 낼 상대가 없다.
     * 엣지 도메인이 함께 있던 시절에는 {@code toolsList(m, domain)} 오버로드가
     * {@code /mcp/{slug}}별로 목록을 갈랐다.
     */
    public ObjectNode toolsList(ObjectMapper m) throws Exception {
        ArrayNode tools = m.createArrayNode();
        for (SimulationModelProvider provider : models.all()) {
            tools.add(descriptor(m, provider.toolName(), provider.description(), provider.inputSchemaJson()));
        }
        for (McpToolProvider provider : independentTools.all()) {
            tools.add(descriptor(m, provider.toolName(), provider.description(), provider.inputSchemaJson()));
        }
        // 아래 셋은 프로바이더 인터페이스를 거치지 않고 SimulationTool이 직접 처리하는 고정 도구다.
        tools.add(descriptor(m, "run_scenario",
                "다중 트럭·분리배출·거주민 구성 등 복잡한 정책 시나리오 실험을 실행한다.",
                RUN_SCENARIO_SCHEMA));
        tools.add(descriptor(m, "list_scenarios",
                "지원하는 시나리오 실험 유형 목록을 반환한다.",
                EMPTY_SCHEMA));
        tools.add(descriptor(m, "update_route_sequence",
                "기존 base 설정에 수거장 방문 순서만 갈아끼워 재실행한다(동적 라우팅 — 정체 구역 우회).",
                UPDATE_ROUTE_SCHEMA));
        ObjectNode result = m.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    /**
     * 주어진 도구의 inputSchema를 파싱해 반환한다 — tools/list가 노출하는 것과 <b>같은</b>
     * 스키마다(단일 원천). 이름을 못 찾으면 {@code null}.
     *
     * <p>실행 시점(tools/call)에서 required 필드를 강제하려면 그 규칙이 어디에 선언돼
     * 있는지를 알아야 하는데, 이미 공개한 JSON Schema가 바로 그 선언이다. 별도 검증
     * 목록을 새로 두면 스키마와 어긋날 수 있으므로 스키마 자체를 재사용한다(A-01).
     */
    public JsonNode inputSchemaFor(ObjectMapper m, String toolName) throws Exception {
        SimulationModelProvider model = models.byToolName(toolName);
        if (model != null) return m.readTree(model.inputSchemaJson());
        McpToolProvider indep = independentTools.byToolName(toolName);
        if (indep != null) return m.readTree(indep.inputSchemaJson());
        return switch (toolName) {
            case "run_scenario" -> m.readTree(RUN_SCENARIO_SCHEMA);
            case "list_scenarios" -> m.readTree(EMPTY_SCHEMA);
            case "update_route_sequence" -> m.readTree(UPDATE_ROUTE_SCHEMA);
            default -> null;
        };
    }

    /**
     * 스키마의 {@code required} 배열에 있는 필드 중 인자에서 빠졌거나 null인 것들을 모은다.
     *
     * <p>{@code hasNonNull}을 쓰므로 필드가 아예 없는 경우와 명시적 null을 똑같이 "누락"으로
     * 본다 — 기본값 12:00으로 조용히 실행되던 문제(A-01)를 막는 지점이다. 스키마가 없거나
     * required가 선언되지 않은 도구는 빈 목록을 돌려준다(강제할 계약이 없음).
     */
    public static java.util.List<String> missingRequired(JsonNode schema, JsonNode args) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (schema == null) return missing;
        JsonNode required = schema.path("required");
        if (!required.isArray()) return missing;
        for (JsonNode f : required) {
            String field = f.asText();
            if (args == null || !args.hasNonNull(field)) missing.add(field);
        }
        return missing;
    }

    /** 공개 JSON Schema의 기본 타입 계약을 실행 시점에도 강제한다. */
    public static java.util.List<String> invalidArgumentTypes(JsonNode schema, JsonNode args) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (schema == null || args == null || args.isMissingNode() || args.isNull()) return errors;
        if ("object".equals(schema.path("type").asText()) && !args.isObject()) {
            errors.add("arguments는 object여야 합니다.");
            return errors;
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return errors;
        properties.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            if (!args.hasNonNull(field)) return;
            JsonNode value = args.get(field);
            JsonNode rule = entry.getValue();
            String expected = rule.path("type").asText("");
            if (!matchesType(expected, value)) {
                errors.add(field + "은(는) " + expected + " 타입이어야 합니다(받은 값: " + value + ").");
                return;
            }
            JsonNode allowed = rule.path("enum");
            if (allowed.isArray() && !contains(allowed, value)) {
                errors.add(field + "에 허용되지 않은 값이 들어왔습니다: " + value);
            }
            if (value.isArray()) {
                String itemType = rule.path("items").path("type").asText("");
                for (int i = 0; i < value.size(); i++) {
                    if (!matchesType(itemType, value.get(i))) {
                        errors.add(field + "[" + i + "]은(는) " + itemType
                                + " 타입이어야 합니다(받은 값: " + value.get(i) + ").");
                    }
                }
            }
        });
        return errors;
    }

    private static boolean matchesType(String expected, JsonNode value) {
        return switch (expected) {
            case "" -> true;
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            case "number" -> value.isNumber() && Double.isFinite(value.doubleValue());
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode candidate : array) if (candidate.equals(value)) return true;
        return false;
    }

    /** list_scenarios 도구 호출 결과(CallToolResult content). */
    public String scenarioListText() {
        return "지원 시나리오 유형: " + String.join(", ", tool.scenarioTypes())
                + "\n구성 프리셋: UNIVERSITY(대학가형), INDUSTRIAL(공단인근형), FAMILY(가족주거형), BALANCED(균형형)";
    }

    private ObjectNode descriptor(ObjectMapper m, String name, String desc, String schemaJson) throws Exception {
        ObjectNode d = m.createObjectNode();
        d.put("name", name);
        d.put("description", desc);
        d.set("inputSchema", m.readTree(schemaJson));
        return d;
    }
}
