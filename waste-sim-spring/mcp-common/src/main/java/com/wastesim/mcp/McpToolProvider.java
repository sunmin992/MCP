package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.tool.ToolResult;

/**
 * "독립 도구/모델" 어댑터 — {@link SimulationModelProvider}와 달리 장량동
 * 쓰레기 도메인의 {@code SimulationConfig}/{@code SimulationConfigValidator}와
 * 완전히 무관한 MCP 도구를 위한 확장점이다(엣지_라즈베리파이_MCP_연동_방법.md §"MCP서버·
 * 모델 분리").
 *
 * <p>{@link SimulationModelProvider}는 {@code run(SimulationConfig)}로 입력이
 * 고정돼 있어 "장량동 시뮬레이션 엔진의 변형"만 꽂을 수 있다 — 아무 모델이나
 * 꽂는 슬롯이 아니다. 반면 이 인터페이스는 원본 JSON 인자를 그대로 받아
 * 구현체가 자기 방식대로 파싱·검증·실행하므로, 라즈베리파이 발열/스로틀링
 * 예측 모델처럼 전혀 다른 입력 스키마를 가진 도구도 {@code SimulationConfig}로
 * 억지 변환되지 않고 그대로 연결할 수 있다.
 *
 * <p>구현체를 스프링 빈으로 하나 등록하면(@Component) {@link McpToolRegistry}가
 * 자동으로 모아 {@link McpToolCatalog}의 tools/list와 {@link McpController}의
 * tools/call 라우팅에 포함시킨다 — 등록 외에 기존 코드를 손댈 필요가 없다
 * (Open/Closed 원칙, {@link SimulationModelProvider}와 같은 설계 철학).
 */
public interface McpToolProvider {

    /** MCP tools/list·tools/call에 노출되는 도구 이름. 예: "get_jangnyang_fixed_subtasks". */
    String toolName();

    /** MCP tools/list에 노출되는 설명. */
    String description();

    /** MCP tools/list에 노출되는 JSON Schema 문자열(inputSchema). 이 도구 전용 스키마를 자유롭게 정의한다. */
    String inputSchemaJson();

    /**
     * 원본 JSON 인자를 받아 이 도구만의 방식으로 파싱·검증·실행한다.
     * 장량동 공용 {@code SimulationConfigValidator}를 거치지 않으므로, 입력
     * 검증이 필요하면 구현체가 직접 수행하고 실패 시 {@link ToolResult#rejected}를
     * 반환한다.
     */
    ToolResult call(JsonNode args);

}
