package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code validate_jangnyang_subtask_answers} — 답변을 서브태스크 규칙으로 검증하고
 * 누락·오류·완료 여부를 돌려준다(FR-126·127·136, SDD 2.18.8).
 *
 * <p>오류를 <b>모아서</b> 돌려주고, 각 오류에 카탈로그의 재질문 문장을 붙인다. 호출자가
 * 재질문 문장을 지어낼 필요가 없게 하는 것이 요점이다 — 필요가 없으면 그렇게 하지
 * 않는다(FR-127·D-47).
 *
 * <p>완료 여부를 두 층으로 돌려준다. {@code complete}는 세트 수준 필수 항목이 다 찼는가이고,
 * {@code sufficient}는 <b>선택된 시나리오 유형이 요구하는 입력</b>까지 다 찼는가다(FR-130).
 * 두 값을 하나로 합치면 "필수는 다 찼는데 왜 실행이 안 되는가"를 호출자가 알 수 없다.
 */
@Component
public class ValidateJangnyangSubtaskAnswersTool implements McpToolProvider {

    private final JangnyangSubtaskCatalog catalog;
    private final JangnyangSubtaskValidator validator;
    private final JangnyangCompletenessChecker checker;

    public ValidateJangnyangSubtaskAnswersTool(JangnyangSubtaskCatalog catalog,
                                               JangnyangSubtaskValidator validator,
                                               JangnyangCompletenessChecker checker) {
        this.catalog = catalog;
        this.validator = validator;
        this.checker = checker;
    }

    @Override public String toolName() { return "validate_jangnyang_subtask_answers"; }

    @Override
    public String description() {
        return "수집한 서브태스크 답변의 자료형·허용 범위·검증 규칙을 서버가 확인하고, "
             + "누락 항목·오류 항목(재질문 문장 포함)·완료 여부를 반환한다. 완료 판정은 "
             + "서버가 하며 호출자의 판단으로 대체할 수 없다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "subtaskSetId": {"type": "string", "description": "세트 ID. 세트와 다르면 거부한다"},
                "version": {"type": "integer", "description": "세트 버전. 세션이 시작한 버전과 달라도 맞춰 주지 않고 거부한다"},
                "answers": {
                  "description": "서브태스크 ID를 키로 한 객체이거나, {subtaskId,value} 원소의 배열",
                  "oneOf": [
                    {"type": "object"},
                    {"type": "array", "items": {"type": "object",
                      "properties": {"subtaskId": {"type": "string"}, "value": {}},
                      "required": ["subtaskId"]}}
                  ]
                }
              },
              "required": ["subtaskSetId", "version", "answers"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        SubtaskToolSupport.Resolved r = SubtaskToolSupport.resolveSet(catalog, args);
        if (!r.ok()) return ToolResult.rejected(r.error());
        var idError = SubtaskToolSupport.checkSetId(r.def(), args);
        if (idError != null) return ToolResult.rejected(idError);

        SubtaskValidationResult result = validator.validate(
                r.def(), SubtaskToolSupport.readAnswers(args), Map.of());
        var verdict = checker.check(r.def(), result.accepted());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subtaskSetId", r.def().subtaskSetId());
        out.put("version", r.def().version());
        out.put("hash", r.def().hash());
        out.put("valid", result.valid());
        out.put("missing", result.missing());
        out.put("errors", SubtaskToolSupport.describe(result.errors()));
        out.put("complete", result.complete());
        out.put("scenarioType", verdict.scenarioType());
        out.put("sufficient", verdict.sufficient());
        out.put("scenarioMissing", verdict.missing());
        return ToolResult.ok(Ordered.copyOf(out));
    }

}
