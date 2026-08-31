package com.wastesim.mcp;

import com.wastesim.model.SimulationConfig;
import com.wastesim.tool.ToolResult;

/**
 * "모델 어댑터" — 시뮬레이션을 실제로 계산하는 엔진 하나를 감싼다
 * (MCP_모델_연결_방법.md §2). 새 모델을 추가할 때마다 {@link McpController}에
 * 분기(switch)를 늘리는 대신, 이 인터페이스의 구현체를 스프링 빈으로 하나
 * 등록하는 것만으로 {@link McpToolCatalog}의 tools/list와 tools/call 라우팅에
 * 자동으로 포함된다(Open/Closed 원칙).
 *
 * <p>공통 검증({@code SimulationConfigValidator})은 모델과 무관하게 항상
 * {@code SimulationTool}이 먼저 통과시키므로, 구현체의 {@link #run}은 이미
 * 검증을 통과한 설정만 받는다 — 모델 고유의 추가 제약이 있다면 이 메서드
 * 안에서 별도로 걸러 {@link ToolResult#rejected}로 반환하면 된다.
 */
public interface SimulationModelProvider {

    /** 내부 식별자(로그·SimulationTool 라우팅용). 예: "java-devs", "python-devs". */
    String modelId();

    /** MCP tools/list·tools/call에 노출되는 도구 이름. 예: "run_waste_simulation". */
    String toolName();

    /** MCP tools/list에 노출되는 설명. */
    String description();

    /** MCP tools/list에 노출되는 JSON Schema 문자열(inputSchema). */
    String inputSchemaJson();

    /** 검증을 통과한 설정으로 실제 시뮬레이션을 실행한다. */
    ToolResult run(SimulationConfig cfg);

}
