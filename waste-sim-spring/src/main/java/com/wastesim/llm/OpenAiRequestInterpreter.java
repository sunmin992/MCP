package com.wastesim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.service.OpenAiService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM으로 요청을 해석한다.
 *
 * <p>프롬프트가 요구하는 것은 셋이다 — 필드, 값, 그리고 <b>근거가 된 원문 조각.</b>
 * 조각을 요구하는 이유는 {@link SpanVerifier}가 그것을 검사해 지어낸 값을 버리기
 * 때문이다. 조각 없이 값만 받으면 그 검사를 할 수 없다.
 */
@Component
@Primary
public class OpenAiRequestInterpreter implements RequestInterpreter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenAiService openAi;

    public OpenAiRequestInterpreter(OpenAiService openAi) {
        this.openAi = openAi;
    }

    @Override
    public RequestExtraction extract(String request, List<String> answerFields)
            throws InterpreterException {
        String prompt = """
                아래 요청에서 시뮬레이션 설정값을 뽑아 JSON으로만 답하라.

                규칙:
                - 요청에 없는 값은 절대 만들지 마라. 확실하지 않으면 빼라.
                - 값마다 근거가 된 요청의 원문 조각을 span에 그대로 옮겨라.
                - span은 요청 문장에 실제로 있는 문자열이어야 한다.

                형식:
                {"values":[{"field":"...","value":...,"span":"..."}],
                 "targetRegion":"","targetDomain":"","requestedConclusion":""}

                쓸 수 있는 field: %s

                요청: %s
                """.formatted(String.join(", ", answerFields), request);
        try {
            String raw = openAi.answerPlain(List.of(), prompt);
            JsonNode n = MAPPER.readTree(raw);
            List<ExtractedValue> values = new ArrayList<>();
            for (JsonNode v : n.path("values")) {
                values.add(new ExtractedValue(
                        v.path("field").asText(null),
                        v.path("value").isNumber() ? v.path("value").numberValue()
                                                   : v.path("value").asText(null),
                        v.path("span").asText(null)));
            }
            return new RequestExtraction(values,
                    n.path("targetRegion").asText(null),
                    n.path("targetDomain").asText(null),
                    n.path("requestedConclusion").asText(null));
        } catch (Exception e) {
            throw new InterpreterException("요청 해석에 실패했습니다", e);
        }
    }
}
