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
     * 이 엔진이 <b>지원하지 않는</b> 설정 필드 이름들. 지원하면 빈 목록.
     *
     * <p>{@link #run}이 같은 판정으로 거부하지만, 그 시점은 사용자가 답을 다 채우고
     * 실행을 누른 뒤다. 수집 계층(고정 서브태스크)은 <b>조립 시점에</b> 이 목록을 물어
     * 미리 막는다 — "질문 목록은 고정하되 고른 엔진에서 미지원인 항목은 명시적으로
     * 안내한다"가 그 규약이다. 두 자리가 같은 판정을 쓰도록 목록을 여기 한 번만 둔다.
     *
     * <p>기본 구현이 빈 목록인 이유: 지원 범위를 좁히는 것은 어댑터의 사정이므로, 새
     * 어댑터가 이 메서드를 몰라도 "전부 지원"으로 동작하는 편이 안전하다 —
     * 지원하지 않는 것을 지원한다고 <b>선언</b>하는 실수는 어댑터를 쓰는 순간 드러나지만,
     * 반대로 기본값이 "전부 미지원"이면 새 어댑터가 아무것도 못 돌리는 상태로 조용히 뜬다.
     */
    default java.util.List<String> unsupported(SimulationConfig cfg) {
        return java.util.List.of();
    }

}
