package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;

import java.util.Map;

/**
 * {@code reset_jangnyang_subtask_session}(선택, FR-137) — 수집 세션을 취소·초기화한다.
 *
 * <p>상태만 CANCELLED로 두지 않고 누적 답변까지 지운다(UT-315). 답변을 남겨 두면 같은 키로
 * 새 수집을 시작했을 때 지난 실험의 값이 조용히 이어지고, 사용자는 자기가 지웠다고 믿는
 * 조건으로 결과를 읽게 된다.
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
public class ResetJangnyangSubtaskSessionTool implements McpToolProvider {

    private final SubtaskSessionService sessions;

    public ResetJangnyangSubtaskSessionTool(SubtaskSessionService sessions) {
        this.sessions = sessions;
    }

    @Override public String toolName() { return "reset_jangnyang_subtask_session"; }

    @Override
    public String description() {
        return "진행 중인 장량동 수집 세션을 취소하고 누적 답변을 지운다. 이후 새로 시작하면 "
             + "이전 답변이 남지 않는다.";
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
        if (sessionKey == null || sessionKey.isBlank()) {
            return ToolResult.rejected(new ValidationError(ErrorCode.MISSING_FIELD, "sessionKey",
                    "세션 키는 필수다 — 키 없이 초기화하면 어느 사용자의 진행을 지우는지 알 수 없다."));
        }
        sessions.cancel(sessionKey);
        return ToolResult.ok(Map.of("sessionKey", sessionKey, "state", SubtaskState.CANCELLED.name()));
    }

}
