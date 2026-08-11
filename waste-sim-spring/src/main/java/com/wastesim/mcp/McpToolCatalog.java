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
                "occupationMix": {"type": "array", "items": {"type": "string"},
                  "description": "직업 구성: BlueCollar/Student/Housewife/NightShift/OfficeWorker"},
                "trafficEnabled": {"type": "boolean", "description": "교통 레이어 사용 여부", "default": false},
                "trafficProfileId": {"type": "string", "description": "예: jangryang-weekday"},
                "routeTravelMinutes": {"type": "integer",
                  "description": "건물 간 기본 이동시간(분, 혼잡 가중치 적용 전). run_waste_simulation_devs(Python 엔진)도 동일 필드 사용", "default": 8},
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

    /** tools/list 결과 노드 { "tools": [ ... ] } */
    /**
     * 등록된 <b>모든</b> 도메인의 도구를 노출한다 — 허브 엔드포인트({@code POST /mcp})용.
     * 도메인 분리 이전부터 있던 시그니처를 그대로 두어 기존 MCP 클라이언트·스크립트가
     * 손대지 않아도 계속 동작하게 한다.
     */
    public ObjectNode toolsList(ObjectMapper m) throws Exception {
        return toolsList(m, null);
    }

    /**
     * 한 도메인의 도구만 노출한다 — {@code POST /mcp/{slug}} 엔드포인트용.
     *
     * @param domain 노출할 도메인. {@code null}이면 필터 없이 전부(허브와 동일).
     */
    public ObjectNode toolsList(ObjectMapper m, McpDomain domain) throws Exception {
        ArrayNode tools = m.createArrayNode();
        for (SimulationModelProvider provider : models.all()) {
            if (matches(domain, provider.domain())) {
                tools.add(descriptor(m, provider.toolName(), provider.description(), provider.inputSchemaJson()));
            }
        }
        // 장량동 도메인과 무관한 독립 도구/모델(McpToolProvider) — 등록된 게 없으면
        // 이 루프는 그냥 아무 일도 안 한다(엣지_라즈베리파이_MCP_연동_방법.md 참고).
        for (McpToolProvider provider : independentTools.all()) {
            if (matches(domain, provider.domain())) {
                tools.add(descriptor(m, provider.toolName(), provider.description(), provider.inputSchemaJson()));
            }
        }
        // 아래 셋은 프로바이더 인터페이스를 거치지 않고 SimulationTool이 직접 처리하는
        // 장량동 고정 도구다 — 레지스트리에 없으므로 도메인도 여기서 직접 못 박는다.
        if (matches(domain, McpDomain.WASTE)) {
            tools.add(descriptor(m, "run_scenario",
                    "다중 트럭·분리배출·거주민 구성 등 복잡한 정책 시나리오 실험을 실행한다.",
                    RUN_SCENARIO_SCHEMA));
            tools.add(descriptor(m, "list_scenarios",
                    "지원하는 시나리오 실험 유형 목록을 반환한다.",
                    EMPTY_SCHEMA));
            tools.add(descriptor(m, "update_route_sequence",
                    "기존 base 설정에 수거장 방문 순서만 갈아끼워 재실행한다(동적 라우팅 — 정체 구역 우회).",
                    UPDATE_ROUTE_SCHEMA));
        }
        ObjectNode result = m.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    /** 필터가 없거나(허브) 도메인이 일치하면 노출 대상. */
    private static boolean matches(McpDomain filter, McpDomain toolDomain) {
        return filter == null || filter == toolDomain;
    }

    /**
     * 어떤 도구 이름이 주어진 도메인에 속하는지 — {@code POST /mcp/{slug}}의
     * tools/call이 <b>남의 도메인 도구를 실행하지 못하게</b> 막는 데 쓴다.
     *
     * <p>tools/list만 필터링하고 call은 열어두면, 목록에 안 보일 뿐 이름만 알면
     * 아무 엔드포인트에서나 실행할 수 있다 — 도메인 분리가 표시상의 구분에
     * 그치고 실제 경계가 되지 못한다. 이 프로젝트가 파라미터 검증에서 취한 태도
     * (잘못된 호출은 연산에 도달하기 전에 구조적으로 차단)를 그대로 적용한다.
     */
    public boolean belongsTo(String toolName, McpDomain domain) {
        if (domain == null) return true; // 허브는 전부 허용
        SimulationModelProvider model = models.byToolName(toolName);
        if (model != null) return model.domain() == domain;
        McpToolProvider indep = independentTools.byToolName(toolName);
        if (indep != null) return indep.domain() == domain;
        // 레지스트리에 없는 이름 = 위 고정 장량동 도구이거나 오타. 전자만 통과시키고
        // 후자는 어차피 McpController가 "알 수 없는 도구"로 떨어뜨린다.
        return domain == McpDomain.WASTE;
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
