package com.countersim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.countersim.mcp.McpToolProvider;
import com.countersim.pes.ExperimentFrame;
import com.countersim.pes.Pes;
import com.countersim.pes.Scenario;
import com.countersim.pes.ScenarioBuilder;
import com.countersim.template.TemplateCatalog;
import com.countersim.mcp.ToolFailure;
import com.countersim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PES 와 실험 프레임을 받아 실행 설정 N벌을 만들고 검증한다.
 *
 * <p>검증을 통과해야 확인 토큰이 나온다. 토큰 없이는 실행하지 않는다.
 *
 * <p>SES 식별자는 도구에 적지 않고 {@link TemplateCatalog} 에서 가져온다. 여기 적어 두면
 * 템플릿 리소스가 바뀌어도 도구가 옛 식별자를 주장하게 되고, "어느 SES 로 만든 설정인가"가
 * 틀린 채로 실행까지 간다.
 */
@Component
public class BuildScenarioTool implements McpToolProvider {

    private final ScenarioBuilder builder;
    private final ScenarioStore store;
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;

    public BuildScenarioTool(ScenarioBuilder builder, ScenarioStore store,
                             TemplateCatalog catalog, ObjectMapper mapper) {
        this.builder = builder;
        this.store = store;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    private static final java.util.Set<String> KNOWN_ORIGINS = java.util.Set.of("USER", "MODEL_DEFAULT", "DERIVED");

    @Override public String toolName() { return "build_scenario"; }

    @Override
    public String description() {
        return "PES 와 실험 프레임으로 실행 설정 N벌을 만들고 검증한다. 실행 설정마다 서브태스크 생성 조건을 다시 "
                + "평가해, 물어야 할 결정이 비어 있으면 막는다 — 기본값을 받아들인 결정도 그 값을 values 에 넣어야 한다. "
                + "통과하면 UNCONFIRMED 로 보관되고, 사람이 확인 화면에서 승인해야 토큰이 나온다. "
                + "purpose에 사용자 목적의 mode·관측 지표를 넣으십시오. COMPARE는 comparisonKey와 comparisonValues도 필요합니다. "
                + "동일 대화의 수정에는 이전 requestId를 재사용하십시오. 작업 명세는 get_templates의 taskManifest를 따릅니다. " + ORIGIN_RULE;
    }

    /** 출처 규칙. 확인 화면이 무엇을 "승인하지 않은 기본값" 으로 경고하는지가 이 규칙으로 정해진다. */
    static final String ORIGIN_RULE = "출처(origins) 규칙: USER = 사용자가 그 값을 직접 말했다. "
            + "MODEL_DEFAULT = 사용자가 값을 말하지 않았다 — LLM 이 템플릿 기본값을 제안했거나, 사용자가 '기본값으로', "
            + "'알아서 해줘' 처럼 값 없이 넘겼다. 확인 화면은 이 값들을 승인하지 않은 기본값으로 따로 보여 준다. "
            + "DERIVED = 사용자가 말한 다른 내용에서 정해졌다(예: '평일 교통량' → trafficMode=APPLY). "
            + "출처를 적지 않은 값은 MODEL_DEFAULT 로 본다.";

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "values":{"type":"object","description":"답변키 → 확정값"},
               "origins":{"type":"object","description":"답변키 → USER / MODEL_DEFAULT / DERIVED. 사용자가 값을 직접 말했을 때만 USER, '기본값으로' 처럼 값 없이 넘긴 것은 MODEL_DEFAULT. 적지 않으면 MODEL_DEFAULT"},
               "variableAnswerKey":{"type":"string","description":"실험 변수의 답변키"},
               "variableValues":{"type":"array","description":"비교할 값들"},
               "observations":{"type":"array","items":{"type":"string"}},
               "requestId":{"type":"string","description":"동일 요청의 후속 생성에 이전 requestId를 다시 넣는다. 변경하면 기존 완료 판정과 승인이 무효가 된다"},
               "purpose":{"type":"object","description":"사용자에게 보여 줄 목적 명세. 미제공은 NOT_SPECIFIED이며 목적 충족을 보장하지 않는다",
                 "properties":{"mode":{"enum":["RUN","COMPARE"]},"comparisonKey":{"type":"string"},"comparisonValues":{"type":"array"},"observations":{"type":"array","items":{"type":"string"}}},"required":["mode","observations"]},
               "userUtterances":{"type":"array","items":{"type":"string"}},
               "evidence":{"type":"object"},
               "request":{"type":"string","description":"사용자가 처음 한 요청 원문(요약하거나 바꿔 쓰지 않는다). 확인 화면이 설정 옆에 그대로 보여 준다 — 사용자는 자기 요청과 설정이 맞는지 보고 승인한다"}},
             "required":["values","variableAnswerKey","variableValues","request"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String requestId = args.path("requestId").asText(args.path("planId").asText(null));
        if (requestId == null || requestId.isBlank()) requestId = "request-" + java.util.UUID.randomUUID();
        store.revise(requestId, com.wastesim.readiness.RevisionInput.of(args));
        // 서버는 사용자의 말을 직접 듣지 못한다. LLM 이 옮겨 주지 않으면 확인 화면에서 요청과 설정을 나란히 볼 수 없다.
        if (args.path("request").asText("").isBlank()) {
            return ToolFailure.of("request", "사용자 요청 원문(request)이 없습니다 — 사용자가 처음 한 요청을 그대로 넣어 다시 부르십시오.");
        }
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            args.path("values").fields().forEachRemaining(
                    e -> values.put(e.getKey(), JsonValues.plain(e.getValue())));

            Map<String, String> origins = new LinkedHashMap<>();
            List<String> badOrigins = new ArrayList<>();
            args.path("origins").fields().forEachRemaining(e -> {
                String o = e.getValue().asText().trim().toUpperCase(java.util.Locale.ROOT);
                if (!KNOWN_ORIGINS.contains(o)) badOrigins.add(e.getKey() + "=" + e.getValue().asText());
                origins.put(e.getKey(), o);
            });
            if (!badOrigins.isEmpty()) {
                return ToolFailure.of("origins", "알 수 없는 출처: " + badOrigins + ". " + ORIGIN_RULE);
            }
            // 출처를 적지 않은 값은 사용자가 말했는지 알 수 없다. 사용자 값으로 넘겨짚으면 확인 화면에서
            // 경고가 사라진다 — 보이는 쪽(MODEL_DEFAULT)으로 둔다.
            values.keySet().forEach(k -> origins.putIfAbsent(k, "MODEL_DEFAULT"));

            List<Object> variableValues = new ArrayList<>();
            args.path("variableValues").forEach(v -> variableValues.add(JsonValues.plain(v)));

            List<String> observations = new ArrayList<>();
            args.path("observations").forEach(v -> observations.add(v.asText()));

            List<String> guardUnverified = List.of();
            if (args.has("userUtterances")) {
                List<String> said = new ArrayList<>();
                args.path("userUtterances").forEach(v -> said.add(v.asText()));
                Map<String, String> evidence = new LinkedHashMap<>();
                args.path("evidence").fields().forEachRemaining(e -> evidence.put(e.getKey(), e.getValue().asText()));
                Map<String, List<Object>> submitted = new LinkedHashMap<>();
                values.forEach((k, v) -> submitted.put(k, List.of(v)));
                submitted.put(args.path("variableAnswerKey").asText(), variableValues);
                var guard = EvidenceGuard.check(submitted, origins, evidence, said, catalog);
                if (!guard.blocks().isEmpty())
                    return ToolFailure.of("evidence", "근거 검사(GUARD): " + String.join("; ", guard.blocks()));
                guardUnverified = guard.unverified();
            }

            Pes pes = new Pes(catalog.sesId(), catalog.sesVersion(), values, origins);
            ExperimentFrame frame = new ExperimentFrame(
                    args.path("variableAnswerKey").asText(), variableValues, observations);

            Scenario scenario = builder.build(pes, frame);

            // 실험 변수는 값이 여럿이라 기본 PES 에 들어 있지 않다. LLM 이 그 값들을 제안했으면
            // (출처 MODEL_DEFAULT) 확인 화면이 경고할 수 있도록, 보관할 PES 에 비교할 값 목록을
            // 함께 싣는다. 식별자와 토큰은 builder 가 이미 계산했으므로 바뀌지 않는다.
            String variableKey = frame.variableAnswerKey();
            if (origins.containsKey(variableKey) && !values.containsKey(variableKey)) {
                Map<String, Object> shown = new LinkedHashMap<>(values);
                shown.put(variableKey, List.copyOf(variableValues));
                pes = new Pes(pes.sesId(), pes.sesVersion(), shown, origins);
            }
            // Server policy: missing/undecidable evidence requires explicit human review.
            // Preserve the same warning in storage, detail API and confirmation screen.
            boolean evidenceProvided = args.has("userUtterances") && args.path("userUtterances").isArray();
            java.util.Set<String> reviewKeys = new java.util.LinkedHashSet<>(guardUnverified);
            if (!evidenceProvided) reviewKeys.addAll(pes.values().keySet());
            if (!evidenceProvided) reviewKeys.add(frame.variableAnswerKey());
            Map<String, Object> reviewValues = new LinkedHashMap<>(pes.values());
            Map<String, String> reviewOrigins = new LinkedHashMap<>(pes.origins());
            if (reviewKeys.contains(frame.variableAnswerKey()))
                reviewValues.put(frame.variableAnswerKey(), List.copyOf(variableValues));
            Map<String, String> evidenceChecks = new LinkedHashMap<>();
            reviewKeys.forEach(k -> evidenceChecks.put(k, evidenceProvided ? "UNVERIFIED" : "NOT_CHECKED"));
            if (reviewValues.containsKey(frame.variableAnswerKey()))
                reviewOrigins.putIfAbsent(frame.variableAnswerKey(), "DERIVED");
            pes = new Pes(pes.sesId(), pes.sesVersion(), reviewValues, reviewOrigins, evidenceChecks);
            var taskReadiness = builder.readiness(pes, frame, scenario);
            var purposeSpec = args.path("purpose").isMissingNode() ? null : args.path("purpose").deepCopy();
            var purposeCheck = com.wastesim.readiness.PurposeCheck.evaluate(purposeSpec, frame.variableAnswerKey(),
                    frame.values(), builder.comparisonValues(frame, scenario), frame.observations(), builder.availableObservations(scenario));
            boolean ready = scenario.valid() && taskReadiness.ready() && purposeCheck.acceptable();
            var proof = mapper.createObjectNode();
            proof.set("taskReadiness", mapper.valueToTree(taskReadiness));
            proof.set("taskDefinitions", mapper.valueToTree(builder.manifest().tasks()));
            proof.set("purposeCheck", mapper.valueToTree(purposeCheck));
            proof.put("manifestVersion", builder.manifest().version());
            proof.put("runArtifactDigest", com.wastesim.readiness.ValidationReceipt.fingerprint(mapper.valueToTree(scenario.runs())));
            proof.put("runArtifactCount", scenario.runs().size());
            proof.put("providerResourceDigest", builder.manifest().resourceFingerprint(BuildScenarioTool.class));
            proof.put("ready", ready);
            proof.put("requestId", requestId);
            final Pes validatedPes = pes;
            java.util.function.Supplier<String> fingerprint = () -> builder.validationFingerprint(validatedPes, frame, scenario, purposeSpec);

            if (ready) store.putValidated(scenario, pes, args.path("request").asText(null), requestId, proof, fingerprint);

            var root = mapper.createObjectNode();
            root.put("sesId", pes.sesId());
            root.put("sesVersion", pes.sesVersion());
            root.put("scenarioId", scenario.scenarioId());
            root.put("valid", ready);
            root.put("requestId", requestId);
            root.set("purposeCheck", mapper.valueToTree(purposeCheck));
            root.set("taskDefinitions", mapper.valueToTree(builder.manifest().tasks()));
            root.set("validation", ready ? store.validation(scenario.scenarioId()) : proof);
            root.set("taskReadiness", mapper.valueToTree(taskReadiness));
            root.put("evidencePolicy", "REQUIRE_CONFIRMATION_FOR_UNVERIFIED");
            root.put("evidenceStatus", !evidenceProvided ? "NOT_CHECKED" : reviewKeys.isEmpty() ? "CHECKED" : "PARTIALLY_VERIFIED");
            root.set("unverifiedKeys", mapper.valueToTree(reviewKeys));
            root.set("unverifiedValues", mapper.valueToTree(pes.unverifiedValues()));
            root.set("reviewRequiredValues", mapper.valueToTree(pes.reviewRequiredValues()));
            root.put("runCount", scenario.runs().size());
            root.set("blocks", mapper.valueToTree(scenario.blocks()));
            root.set("backVerificationBlocks",
                    mapper.valueToTree(scenario.backVerificationBlocks()));
            root.set("unapprovedDefaults", mapper.valueToTree(pes.unapprovedDefaults()));
            // 토큰은 여기서 나오지 않는다. 검증을 통과해도 아직 미확인이며,
            // 사람이 확인 화면에서 승인해야 발급된다(ScenarioConfirmController → ConfirmScenarioTool).
            root.put("state", store.entry(scenario.scenarioId())
                    .map(ScenarioStore.Entry::state).orElse("INVALID"));
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (IllegalArgumentException e) {
            return ToolFailure.of("values", e.getMessage());
        } catch (Exception e) {
            return ToolFailure.of("scenario", "시나리오 생성에 실패했습니다: " + e.getMessage());
        }
    }
}
