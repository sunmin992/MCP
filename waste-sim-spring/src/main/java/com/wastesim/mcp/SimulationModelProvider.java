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

    /**
     * 이 모델이 속한 도메인 — {@code POST /mcp/{slug}} 엔드포인트와 tools/list
     * 필터링에 쓰인다({@link McpDomain}).
     *
     * <p>기본값이 {@link McpDomain#WASTE}인 것은 편의가 아니라 <b>구조적 사실</b>이다.
     * 이 인터페이스는 입력이 {@link SimulationConfig}(수거 시각·트럭·민원 임계 등
     * 장량동 전용 스키마)로 고정돼 있어, 여기 꽂히는 모델은 정의상 장량동 계열이다.
     * 다른 도메인의 모델은 이 슬롯이 아니라 {@link McpToolProvider}로 붙는다.
     */
    default McpDomain domain() {
        return McpDomain.WASTE;
    }
}
