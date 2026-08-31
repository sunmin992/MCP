package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code submit_jangnyang_subtask_answer}(선택, FR-137) — 서버가 세션을 들고 있는 수집
 * 흐름을 외부 MCP 클라이언트에도 열어 준다.
 *
 * <p>채팅 경로만 쓸 때는 {@code ChatController}가 세션을 관리하므로 이 도구가 없어도 된다.
 * 필요한 경우는 외부 클라이언트가 <b>수집 흐름을 스스로 몰고 갈 때</b>다 — 그때도 세션·상태
 * 전이·검증은 서버가 하고, 클라이언트는 답변만 넣는다. 클라이언트가 자기 쪽에서 상태를
 * 들고 있게 하면 상태 축의 fail-closed(D-52)가 클라이언트 구현에 좌우된다.
 *
 * <p>{@code sessionKey}가 없는 호출은 거부한다 — 기본 키로 대신 실행하면 v1.12까지의
 * 단일 {@code default} 세션이 되살아나 세션 격리(NFR-18)가 무너진다.
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
public class SubmitJangnyangSubtaskAnswerTool implements McpToolProvider {

    private final SubtaskSessionService sessions;

    public SubmitJangnyangSubtaskAnswerTool(SubtaskSessionService sessions) {
        this.sessions = sessions;
    }

    @Override public String toolName() { return "submit_jangnyang_subtask_answer"; }

    @Override
    public String description() {
        return "진행 중인 장량동 수집 세션에 서브태스크 답변 하나를 제출한다. 서버가 검증하고 "
             + "다음 질문 또는 같은 재질문 문장과 진행 상태를 돌려준다. 세션이 없으면 "
             + "start=true로 새 수집을 시작할 수 있다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "sessionKey": {"type": "string", "description": "수집 세션 키(사용자·연결 단위). 생략하면 거부한다"},
                "start": {"type": "boolean", "description": "true면 새 수집을 시작하고 첫 질문을 돌려준다", "default": false},
                "subtaskId": {"type": "string", "description": "답변 대상 서브태스크 ID. 생략하면 세션이 지금 묻고 있는 항목"},
                "version": {"type": "integer", "description": "답변이 주장하는 세트 버전. 세션 버전과 다르면 거부한다"},
                "value": {"description": "사용자 답변의 구조화 값(문자열·숫자·불리언·배열)"}
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
                    "세션 키는 필수다 — 기본 키로 대신 실행하면 세션 격리가 무너진다(NFR-18)."));
        }
        if (args.path("start").asBoolean(false)) {
            return describe(sessions.start(sessionKey));
        }
        if (!args.hasNonNull("value")) {
            return ToolResult.rejected(new ValidationError(ErrorCode.MISSING_FIELD, "value",
                    "답변 값이 없다. 새 수집을 시작하려면 start=true를 쓴다."));
        }
        Integer version = args.path("version").isIntegralNumber() ? args.path("version").asInt() : null;
        SubtaskSessionService.Step step = sessions.submit(sessionKey,
                args.path("subtaskId").asText(null), toJava(args.get("value")), version);
        return describe(step);
    }

    private ToolResult describe(SubtaskSessionService.Step step) {
        if (!step.ok()) {
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "session", step.rejection()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("progress", SubtaskSessionService.describe(step.progress()));
        // 질문은 카탈로그의 문장을 그대로 실어 보낸다 — 도구가 문장을 다시 쓰지 않는다(D-44).
        out.put("question", step.question() == null ? null : SubtaskToolSupport.describe(step.question()));
        out.put("errors", SubtaskToolSupport.describe(step.errors()));
        out.put("readyToBuild", step.readyToBuild());
        return ToolResult.ok(Ordered.copyOf(out));
    }

    private static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (JsonNode item : n) list.add(toJava(item));
            return list;
        }
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber()) return n.longValue();
        if (n.isNumber()) return n.doubleValue();
        return n.asText();
    }

}
