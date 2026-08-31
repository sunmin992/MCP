package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;

/**
 * {@code get_jangnyang_subtask_progress}(선택, FR-137) — 세션 진행 상태를 조회한다(FR-128).
 *
 * <p>제출과 조회를 한 도구로 합치지 않은 이유: 조회는 상태를 바꾸지 않는데, 합치면 "값 없이
 * 부르면 조회"라는 암묵 규약이 생기고 클라이언트가 실수로 상태를 옮길 수 있다.
 *
 * <p><b>이 도구는 스프링 빈으로 등록하지 않는다</b> — 그래서 tools/list에 나오지 않는다.
 *
 * FR-137은 이 3종을 "권장"으로 두지만, FR-136·IT-77이 고정한 노출 규모는 허브 14종
 * (모델 2 + 고정 장량동 3 + 엣지 6 + 서브태스크 필수 3)이다. 빈으로 등록하면 17종이
 * 되어 그 계약이 깨진다. 구현은 남기되 노출은 하지 않는 쪽을 골랐다 — 지우면 다시 쓸 때
 * 처음부터 짜야 하고, 등록하면 공개 계약이 어긋난다.
 *
 * <p><b>다시 노출하려면</b>: 이 클래스에 스프링 컴포넌트 애너테이션을 붙이고
 * McpToolExposureTest의 기대 개수를 함께 올린다. 두 곳을 같이 고치게 해서, 노출 규모가
 * 조용히 바뀌는 일이 없게 한다.
 *
 * <p>등록하지 않아도 동작은 UnregisteredSubtaskToolsTest가 계속 검증한다 — 쓰지 않는 코드가
 * 조용히 썩는 것을 막기 위해서다.
 */
public class GetJangnyangSubtaskProgressTool implements McpToolProvider {

    private final SubtaskSessionService sessions;

    public GetJangnyangSubtaskProgressTool(SubtaskSessionService sessions) {
        this.sessions = sessions;
    }

    @Override public String toolName() { return "get_jangnyang_subtask_progress"; }

    @Override
    public String description() {
        return "진행 중인 장량동 수집 세션의 상태를 반환한다 — 세트 ID·버전·현재 서브태스크·"
             + "순서·전체 개수·진행률·누적 답변·검증 오류 목록.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "sessionKey": {"type": "string", "description": "수집 세션 키(사용자·연결 단위)"}
              },
              "required": ["sessionKey"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String sessionKey = args == null ? null : args.path("sessionKey").asText(null);
        SubtaskProgress p = sessions.progress(sessionKey);
        if (p == null) {
            // 빈 진행 상태를 지어내지 않는다 — 없는 세션을 "0% 진행 중"으로 돌려주면
            // 클라이언트는 답을 넣을 수 있다고 믿는다.
            return ToolResult.rejected(SubtaskSessionService.noSession(sessionKey));
        }
        return ToolResult.ok(SubtaskSessionService.describe(p));
    }

}
