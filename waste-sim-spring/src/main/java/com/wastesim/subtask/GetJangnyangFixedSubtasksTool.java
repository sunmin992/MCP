package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * {@code get_jangnyang_fixed_subtasks} — 버전이 고정된 장량동 서브태스크 세트를 반환한다
 * (FR-120·121·136, SDD 2.18.8).
 *
 * <p><b>이 클래스에 LLM 호출이 없다는 사실이 계약의 본체다</b>(D-44, NFR-17·UT-301).
 * 질문 문장은 카탈로그가 리소스에서 읽은 것을 그대로 실어 나르며, 백엔드 모델이 무엇이든
 * 응답은 같다. 응답에 함께 실리는 해시로 클라이언트와 테스트가 세트가 바뀌지 않았음을
 * 확인한다.
 *
 * <p>MCP 엔드포인트({@code POST /mcp})에 노출된다(FR-136).
 */
@Component
public class GetJangnyangFixedSubtasksTool implements McpToolProvider {

    private final JangnyangSubtaskCatalog catalog;

    public GetJangnyangFixedSubtasksTool(JangnyangSubtaskCatalog catalog) {
        this.catalog = catalog;
    }

    @Override public String toolName() { return "get_jangnyang_fixed_subtasks"; }

    @Override
    public String description() {
        return "장량동 시뮬레이터를 구성하기 위해 사용자에게 물어야 할 고정 서브태스크 세트를 "
             + "반환한다. 질문 문장·순서·필수 여부·허용 범위·재질문 문장은 서버가 소유하며, "
             + "호출자는 이 문장을 그대로 사용자에게 전달해야 한다 — 추가·삭제·수정·재정렬·"
             + "병합·생략은 허용되지 않는다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "version": {"type": "integer",
                  "description": "조회할 세트 버전. 생략하면 최신 버전. 존재하지 않는 버전은 가까운 값으로 대체하지 않고 거부한다"}
              }
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        SubtaskToolSupport.Resolved r = SubtaskToolSupport.resolveSet(catalog, args);
        if (!r.ok()) return ToolResult.rejected(r.error());
        return ToolResult.ok(SubtaskToolSupport.describe(r.def()));
    }

}
