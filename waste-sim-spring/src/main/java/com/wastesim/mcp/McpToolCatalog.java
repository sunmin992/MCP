package com.wastesim.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.tool.SimulationTool;
import org.springframework.stereotype.Component;

/**
 * MCP 도구 카탈로그 — tools/list 로 노출되는 도구 정의와 JSON Schema.
 *  - run_waste_simulation : 기본 정책 시뮬레이션(다중 시드)
 *  - run_scenario         : 복잡한 시나리오 실험(구성·sweep·그리드·트럭·분리배출 등)
 *  - list_scenarios       : 지원 시나리오 유형·프리셋 조회
 */
@Component
public class McpToolCatalog {

    private final SimulationTool tool;

    public McpToolCatalog(SimulationTool tool) {
        this.tool = tool;
    }

    private static final String RUN_SIM_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "collectionTime": {"type": "string", "description": "수거 시각 HH:MM (예: 12:00)"},
                "days": {"type": "integer", "description": "시뮬레이션 기간(일)", "default": 30},
                "seeds": {"type": "integer", "description": "반복 횟수", "default": 30},
                "leaveSigma": {"type": "number", "description": "외출 시각 표준편차(분)", "default": 30.0},
                "wasteSigma": {"type": "number", "description": "일일 배출량 표준편차(kg)", "default": 0.3},
                "capacity": {"type": "number", "description": "수거장 용량(kg)", "default": 30.0},
                "threshold": {"type": "number", "description": "민원 임계 적재율(0~1)", "default": 0.8},
                "numBuildings": {"type": "integer", "default": 4},
                "residentsPerBuilding": {"type": "integer", "default": 25},
                "occupationMix": {"type": "array", "items": {"type": "string"},
                  "description": "직업 구성: BlueCollar/Student/Housewife/NightShift/OfficeWorker"}
              },
              "required": ["collectionTime"]
            }
            """;

    private static final String RUN_SCENARIO_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "type": {"type": "string",
                  "enum": ["occupation-mix","collection-sweep","behavior-grid","infra-grid","density",
                           "collection-schedule","multi-truck","waste-separation","new-occupations",
                           "coupling-variants","monthly-waste"],
                  "description": "실행할 시나리오 실험 유형"},
                "days": {"type": "integer", "default": 30},
                "seeds": {"type": "integer", "default": 10},
                "capacity": {"type": "number", "default": 30.0},
                "threshold": {"type": "number", "default": 0.8},
                "leaveSigma": {"type": "number", "default": 30.0},
                "wasteSigma": {"type": "number", "default": 0.3},
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
    public ObjectNode toolsList(ObjectMapper m) throws Exception {
        ArrayNode tools = m.createArrayNode();
        tools.add(descriptor(m, "run_waste_simulation",
                "원룸촌 생활쓰레기 DEVS 시뮬레이션을 지정 수거 시각으로 실행하고 월간 민원 통계를 반환한다.",
                RUN_SIM_SCHEMA));
        tools.add(descriptor(m, "run_scenario",
                "다중 트럭·분리배출·거주민 구성 등 복잡한 정책 시나리오 실험을 실행한다.",
                RUN_SCENARIO_SCHEMA));
        tools.add(descriptor(m, "list_scenarios",
                "지원하는 시나리오 실험 유형 목록을 반환한다.",
                EMPTY_SCHEMA));
        ObjectNode result = m.createObjectNode();
        result.set("tools", tools);
        return result;
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
