package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code build_jangnyang_scenario} — 검증된 답변으로 시나리오 명세와 실행 설정을 만든다
 * (FR-131~134·136, SDD 2.18.8).
 *
 * <p>응답에 {@code appliedDefaults}·{@code assumptions}를 반드시 함께 싣는다(D-53).
 * 서버가 채운 값을 숨기고 성공 응답만 내면 호출자는 자기 실험의 조건을 모른 채 결과를
 * 읽는다 — 이 도구의 출력이 곧 미리보기의 내용이 된다.
 *
 * <p>미충족이면 <b>부분 명세를 만들지 않고 거부한다</b>(UT-324). 반쯤 채워진 명세를
 * 돌려주면 호출자가 "일단 받았으니 실행해도 되겠지"로 읽을 여지가 생긴다.
 */
@Component
public class BuildJangnyangScenarioTool implements McpToolProvider {

    private final JangnyangSubtaskCatalog catalog;
    private final JangnyangSubtaskValidator validator;
    private final JangnyangScenarioBuilder builder;

    public BuildJangnyangScenarioTool(JangnyangSubtaskCatalog catalog,
                                      JangnyangSubtaskValidator validator,
                                      JangnyangScenarioBuilder builder) {
        this.catalog = catalog;
        this.validator = validator;
        this.builder = builder;
    }

    @Override public String toolName() { return "build_jangnyang_scenario"; }

    @Override
    public String description() {
        return "검증을 통과한 서브태스크 답변으로 장량동 시나리오 명세와 실행 설정"
             + "(SimulationConfig)을 생성한다. 서버가 채운 기본값과 가정, 선택된 실행 도구·"
             + "시나리오 유형·엔진을 함께 반환한다. 필수 입력이 모자라면 부분 명세를 만들지 "
             + "않고 거부한다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "subtaskSetId": {"type": "string", "description": "세트 ID"},
                "version": {"type": "integer", "description": "세트 버전"},
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

        SubtaskValidationResult validated = validator.validate(
                r.def(), SubtaskToolSupport.readAnswers(args), Map.of());
        if (!validated.valid()) {
            // 서브태스크 검증에서 이미 걸린 답으로는 조립하지 않는다 — 두 겹 검증의
            // 첫 겹을 건너뛰면 조립기가 잘못된 값을 설정으로 옮긴다(D-48).
            return ToolResult.rejected(new SubtaskValidationErrors(validated).asList());
        }

        JangnyangScenarioBuilder.BuildOutcome outcome = builder.build(r.def(), validated.accepted());
        if (!outcome.ok()) {
            return ToolResult.rejected(outcome.asValidationErrors());
        }

        JangnyangScenarioSpec spec = outcome.spec();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenarioSpec", spec.toPreviewMap());
        out.put("simulationConfig", spec.toSimulationConfig());
        out.put("appliedDefaults", spec.toPreviewMap().get("appliedDefaults"));
        out.put("assumptions", spec.assumptions());
        out.put("toolName", spec.toolName());
        out.put("scenarioType", spec.scenarioType());
        out.put("engineId", spec.engineId());
        out.put("preview", spec.previewText());
        return ToolResult.ok(Ordered.copyOf(out));
    }


    /** 서브태스크 오류를 공통 구조화 오류로 옮기는 얇은 어댑터(재질문 문장을 잃지 않는다). */
    private record SubtaskValidationErrors(SubtaskValidationResult result) {
        java.util.List<com.wastesim.tool.ValidationError> asList() {
            java.util.List<com.wastesim.tool.ValidationError> out = new java.util.ArrayList<>();
            for (SubtaskError e : result.errors()) {
                out.add(new com.wastesim.tool.ValidationError(e.code(), e.subtaskId(),
                        e.reason() + " / " + e.retryQuestion()));
            }
            return out;
        }
    }
}
