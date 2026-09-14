package com.wastesim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.subtask.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 파일 배치 브리지: 실제 검증기·질문 계획·조립기를 실행하며 엔진 실행은 하지 않는다. */
public class ExtractionBenchmarkBridgeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluateBatch() throws Exception {
        String input = System.getProperty("benchmark.input");
        assumeTrue(input != null, "벤치마크 배치가 지정되지 않음");
        List<Object> results = new ArrayList<>();
        for (JsonNode row : mapper.readTree(Files.readString(Path.of(input)))) {
            List<ExtractedValue> extracted = new ArrayList<>();
            for (JsonNode v : row.path("values")) {
                extracted.add(new ExtractedValue(v.path("field").asText(),
                        mapper.convertValue(v.get("value"), Object.class), v.path("span").asText()));
            }
            JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
            SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
            RequestExtraction extraction = new RequestExtraction(extracted, "", "", "");
            BlueprintComposer composer = new BlueprintComposer(sessions, catalog, (r, f) -> extraction);
            String request = row.path("request").asText();
            var outcome = composer.compose("benchmark", request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.path("id").asText());
            result.put("mustAsk", outcome.mustAsk());
            result.put("defaults", outcome.appliedDefaults());
            result.put("initialAnswers", answers(sessions, catalog));
            result.put("initialBuild", sessions.build("benchmark").ok());
            // 사전에 정한 추가 사용자 답변만 제출한다. 추출 누락을 정답으로 보충하지 않는다.
            List<Object> followupErrors = new ArrayList<>();
            if (!Boolean.TRUE.equals(result.get("initialBuild"))) {
                var it = row.path("followups").fields();
                while (it.hasNext()) {
                    var e = it.next();
                    var task = catalog.latest().byAnswerField(e.getKey());
                    if (task == null) throw new IllegalArgumentException("Unknown followup " + e.getKey());
                    var submitted = sessions.submit("benchmark", task.id(), mapper.convertValue(e.getValue(), Object.class), null);
                    followupErrors.addAll(submitted.errors());
                }
            }
            var built = sessions.build("benchmark");
            result.put("followupErrors", followupErrors);
            result.put("finalBuild", Boolean.TRUE.equals(result.get("initialBuild")) || built.ok());
            result.put("buildMessage", built.message());
            result.put("finalAnswers", answers(sessions, catalog));
            var spec = built.spec() != null ? built.spec() : sessions.activeSession("benchmark").spec();
            result.put("scenario", spec == null ? null : mapper.valueToTree(spec));
            results.add(result);
        }
        Files.writeString(Path.of(System.getProperty("benchmark.output")), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    private Map<String,Object> answers(SubtaskSessionService sessions, JangnyangSubtaskCatalog catalog) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (var task : catalog.latest().subtasks()) {
            var a = sessions.activeSession("benchmark").answers().get(task.id());
            if (a != null && a.valid()) result.put(task.answerField(), a.value());
        }
        return result;
    }
}
