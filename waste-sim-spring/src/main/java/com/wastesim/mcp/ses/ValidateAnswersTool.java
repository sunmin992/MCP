package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskTemplate;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 답변을 템플릿 계약에 대조해 정규화하거나 거절한다.
 *
 * <p>보정하지 않는다 — 허용값 밖이면 사유 코드와 허용값을 함께 돌려주고 다시 묻게 한다.
 */
@Component
public class ValidateAnswersTool implements McpToolProvider {

    private final TemplateCatalog catalog;
    private final AnswerNormalizer normalizer;
    private final ObjectMapper mapper;

    public ValidateAnswersTool(TemplateCatalog catalog, AnswerNormalizer normalizer,
                               ObjectMapper mapper) {
        this.catalog = catalog;
        this.normalizer = normalizer;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "validate_answers"; }

    @Override
    public String description() {
        return "답변을 템플릿의 허용값·범위에 대조한다. 맞지 않으면 사유 코드와 허용값을 "
                + "돌려주며, 가까운 값으로 보정하지 않는다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "answers":{"type":"object",
                 "description":"답변키 → 원문 문자열. 예: {\\"truckType\\":\\"SMALL_1TON\\"}"}},
             "required":["answers"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            var normalized = mapper.createObjectNode();
            var rejected = mapper.createArrayNode();

            args.path("answers").fields().forEachRemaining(e -> {
                String key = e.getKey();
                String raw = e.getValue().asText();
                Optional<SubtaskTemplate> t = catalog.byAnswerKey(key);
                if (t.isEmpty()) {
                    var r = rejected.addObject();
                    r.put("answerKey", key);
                    r.put("code", "UNKNOWN_ANSWER_KEY");
                    r.put("message", "템플릿에 없는 답변키입니다: " + key);
                    return;
                }
                var result = normalizer.normalize(t.get(), raw);
                if (result.ok()) {
                    normalized.putPOJO(key, result.value());
                } else {
                    var r = rejected.addObject();
                    r.put("answerKey", key);
                    r.put("code", result.errorCode());
                    r.put("message", result.message());
                }
            });

            var root = mapper.createObjectNode();
            root.put("valid", rejected.isEmpty());
            root.set("normalized", normalized);
            root.set("rejected", rejected);
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("answers", "답변 검증에 실패했습니다: " + e.getMessage());
        }
    }
}
