package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.pes.ExperimentFrame;
import com.wastesim.pes.Pes;
import com.wastesim.pes.Scenario;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.plan.PlanRounds;
import com.wastesim.template.EvidenceGuard;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final PlanRounds rounds;

    public BuildScenarioTool(ScenarioBuilder builder, ScenarioStore store,
                             TemplateCatalog catalog, ObjectMapper mapper) {
        this(builder, store, catalog, mapper, null);
    }

    @Autowired
    public BuildScenarioTool(ScenarioBuilder builder, ScenarioStore store,
                             TemplateCatalog catalog, ObjectMapper mapper, PlanRounds rounds) {
        this.builder = builder;
        this.store = store;
        this.catalog = catalog;
        this.mapper = mapper;
        this.rounds = rounds;
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
        return com.wastesim.contract.RequirementContract.toolSchema("""
            {"type":"object",
             "properties":{
               "regionId":{"type":"string","description":"지역 데이터 ID 또는 사용자 지역명. 미등록 지역은 공통 기본값의 가상 블록으로 구성"},
               "scenarioMode":{"enum":["ASSUMED_BLOCK","PUBLIC_DATA_BLOCK"],"description":"가상 블록 또는 공개 지도 블록 구성 시 지정"},
               "selection":{"type":"object","description":"Forward broker selection unchanged; client assertion, not approval"},
               "requirements":{"type":"object","description":"Original broker requirements, schemaVersion 1.0.0"},
               "matchId":{"type":"string","description":"Broker round.matchId, for trace correlation; not an authenticated selection"},
               "values":{"type":"object","description":"답변키 → 확정값"},
               "origins":{"type":"object","description":"답변키 → USER / MODEL_DEFAULT / DERIVED. 사용자가 값을 직접 말했을 때만 USER, '기본값으로' 처럼 값 없이 넘긴 것은 MODEL_DEFAULT. 적지 않으면 MODEL_DEFAULT"},
               "variableAnswerKey":{"type":"string","description":"실험 변수의 답변키"},
               "variableValues":{"type":"array","description":"비교할 값들"},
               "observations":{"type":"array","items":{"type":"string"}},
               "requestId":{"type":"string","description":"동일 요청의 후속 생성에 이전 requestId를 다시 넣는다. 변경하면 기존 완료 판정과 승인이 무효가 된다"},
               "purpose":{"type":"object","description":"사용자에게 보여 줄 목적 명세. 미제공은 NOT_SPECIFIED이며 목적 충족을 보장하지 않는다",
                 "properties":{"mode":{"enum":["RUN","COMPARE"]},"comparisonKey":{"type":"string"},"comparisonValues":{"type":"array"},"observations":{"type":"array","items":{"type":"string"}}},"required":["mode","observations"]},
               "request":{"type":"string","description":"사용자가 처음 한 요청 원문(요약하거나 바꿔 쓰지 않는다). 확인 화면이 설정 옆에 그대로 보여 준다 — 사용자는 자기 요청과 설정이 맞는지 보고 승인한다"},
               "userUtterances":{"type":"array","items":{"type":"string"},"description":"근거 검사용 사용자 발화 원문 목록. 클라이언트가 대화 기록에서 넣는다. 생략하면 NOT_CHECKED 로 보관하고 사용자 확인이 필요하다. 있으면 evidence 를 검사한다"},
               "evidence":{"type":"object","description":"답변키 → 그 값의 근거가 된 사용자 발화 구절(그대로 옮긴다). USER 값과, 조건부 결정에 기본값을 쓸 때 필요하다"},
               "planId":{"type":"string","description":"plan_subtasks 가 준 planId. 주면 생성 결과와 이후 확인 · 실행을 그 요청의 기록에 잇는다"}},
             "required":["values","variableAnswerKey","variableValues","request"]}
            """);
    }

    @Override
    public ToolResult call(JsonNode args) {
        String requestId = args.path("requestId").asText(args.path("planId").asText(null));
        if (requestId == null || requestId.isBlank()) requestId = "request-" + java.util.UUID.randomUUID();
        store.revise(requestId, com.wastesim.readiness.RevisionInput.of(args));
        String planId = JsonValues.text(args, "planId");
        // 서버는 사용자의 말을 직접 듣지 못한다. LLM 이 옮겨 주지 않으면 확인 화면에서 요청과 설정을 나란히 볼 수 없다.
        if (args.path("request").asText("").isBlank()) {
            return rejected(planId, "REQUEST", null,
                    ToolFailure.of("request", "사용자 요청 원문(request)이 없습니다 — 사용자가 처음 한 요청을 그대로 넣어 다시 부르십시오."));
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
                return rejected(planId, "ORIGIN", null,
                        ToolFailure.of("origins", "알 수 없는 출처: " + badOrigins + ". " + ORIGIN_RULE));
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
                    return rejected(planId, "GUARD", guard.blocks().size(),
                            ToolFailure.of("evidence", "근거 검사(GUARD): " + String.join("; ", guard.blocks())));
                guardUnverified = guard.unverified();
            }

            String regionId = args.path("regionId").asText(args.path("requirements").path("regionId").asText(
                    args.path("selection").path("requirements").path("regionId").asText("jangnyang")));
            var region = new com.wastesim.region.RegionCatalog().region(regionId);
            region.path("defaults").fields().forEachRemaining(e -> {
                String key = catalog.all().stream().filter(t -> t.configField().equals(e.getKey()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("지역 필드에 템플릿이 없습니다: " + e.getKey())).answerKey();
                if (!values.containsKey(key) && !key.equals(args.path("variableAnswerKey").asText())) {
                    values.put(key, JsonValues.plain(e.getValue()));
                    origins.put(key, "REGION_DATA");
                }
            });
            Pes pes = new Pes(catalog.sesId(), catalog.sesVersion(), values, origins);
            ExperimentFrame frame = new ExperimentFrame(
                    args.path("variableAnswerKey").asText(), variableValues, observations);

            Scenario scenario = builder.build(pes, frame, region);

            // 빌더가 가지친 쪽이라 실행 설정에서 뺀 값(기본값, 또는 다른 비교 값에서만 생기는 결정의 값 —
            // 이쪽은 늘 일부 실행에서만 빠진다). 모든 실행에서 뺐으면 보관 PES 에서도 지운다 —
            // 남기면 확인 화면 · 실행 결과 메모가 적용되지 않은 값에 사용자의 동의를 받는다.
            // 일부 실행에서만 뺐으면 다른 실행에 쓰이므로 남기고, 어느 실행에서 뺐는지만 알린다.
            var dropped = mapper.createObjectNode();
            for (var d : scenario.droppedDefaults().entrySet()) {
                Object v = values.get(d.getKey());
                if (d.getValue().size() == scenario.runs().size()) {
                    values.remove(d.getKey());
                    origins.remove(d.getKey());
                    dropped.set(d.getKey(), mapper.valueToTree(v));
                } else {
                    var n = dropped.putObject(d.getKey());
                    n.set("value", mapper.valueToTree(v));
                    n.set("droppedIn", mapper.valueToTree(d.getValue()));
                }
            }
            pes = new Pes(pes.sesId(), pes.sesVersion(), values, origins);

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
            var providerCard = new com.wastesim.capability.CapabilityCardLoader().rawJson();
            var requirements = com.wastesim.contract.SelectionContract.requirements(args, providerCard);
            var requirementReport = com.wastesim.contract.SelectionContract.verify(requirements, providerCard,
                    mapper.valueToTree(scenario.runs()), observations, builder.availableObservations(scenario));
            var requirementChecks = requirementReport.checks();
            boolean requirementReady = requirementReport.ready();
            boolean ready = requirementReady && scenario.valid() && taskReadiness.ready() && purposeCheck.acceptable();
            var proof = mapper.createObjectNode();
            proof.put("matchId", args.path("selection").path("matchId").asText(args.path("matchId").asText(null)));
            proof.set("selection", args.path("selection"));
            proof.set("requirementErrors", mapper.valueToTree(requirementReport.errors()));
            proof.set("requirements", requirements);
            proof.set("requirementChecks", mapper.valueToTree(requirementChecks));
            proof.put("requirementReady", requirementReady);
            proof.set("region", region);
            boolean assumed = region.path("synthetic").asBoolean() || args.path("scenarioMode").asText().equals("ASSUMED_BLOCK");
            boolean mapped = !assumed && region.path("basis").asText().equals("PUBLIC_MAP_DATA");
            proof.put("scenarioMode", assumed ? "ASSUMED_BLOCK" : mapped ? "PUBLIC_DATA_BLOCK" : "CONFIGURED_MODEL");
            proof.put("dataNotice", assumed ? "요청 지역명의 가상 블록입니다. 공통 기본값과 지정한 입력을 사용하며 실제 지역의 지리·인구·도로·교통을 재현하지 않습니다."
                    : mapped ? "공개 지도(OpenStreetMap)에서 찾은 실제 주거 블록 " + region.path("defaults").path("numBuildings").asInt()
                        + "동으로 구성했습니다. 건물 수와 도로 이동은 지도 자료, 세대 수는 건물 면적·층수로 추정, 정차시간과 배출 시간대는 가정값입니다. 동 전체나 실측 민원을 재현하지 않습니다."
                    : "제공자 모델과 지정한 입력을 사용합니다.");
            var structural = proof.putArray("structureSelection");
            for (Object comparison : frame.values()) {
                var selectedValues = new LinkedHashMap<String, Object>(values);
                selectedValues.put(frame.variableAnswerKey(), comparison);
                var selection = structural.addObject();
                selection.set("comparisonValue", mapper.valueToTree(comparison));
                var decisions = selection.putArray("decisions");
                for (var instance : builder.selection(selectedValues).instances()) {
                    var template = catalog.byAnswerKey(instance.answerKey()).orElseThrow();
                    var decision = decisions.addObject();
                    decision.put("sesPath", template.sesPath());
                    decision.put("nodeKind", template.nodeKind());
                    decision.put("configField", template.configField());
                    decision.put("decisionRole", template.decisionRole());
                    decision.put("selected", instance.status() != com.wastesim.template.SubtaskStatus.NOT_GENERATED);
                    decision.set("input", mapper.valueToTree(instance));
                }
            }
            proof.set("taskReadiness", mapper.valueToTree(taskReadiness));
            proof.set("taskDefinitions", mapper.valueToTree(builder.manifest().tasks()));
            proof.set("purposeCheck", mapper.valueToTree(purposeCheck));
            proof.put("manifestVersion", builder.manifest().version());
            proof.put("runArtifactDigest", com.wastesim.readiness.ValidationReceipt.fingerprint(mapper.valueToTree(scenario.runs())));
            proof.put("runArtifactCount", scenario.runs().size());
            proof.put("providerResourceDigest", builder.manifest().resourceFingerprint(BuildScenarioTool.class));
            proof.put("ready", ready);
            proof.put("requestId", requestId);
            proof.set("decisionRoles", roleGroups(pes));
            final Pes validatedPes = pes;
            java.util.function.Supplier<String> fingerprint = () -> builder.validationFingerprint(validatedPes, frame, scenario, purposeSpec)
                    + ":" + com.wastesim.readiness.ValidationReceipt.fingerprint(requirements);
            final String linkedPlan = planId;
            final var baseFingerprint = fingerprint;
            fingerprint = () -> baseFingerprint.get() + ":" + (rounds == null || linkedPlan == null
                    ? "unlinked" : rounds.currentRound(linkedPlan).map(Object::toString).orElse("expired"));

            boolean planIdUnknown = false;
            if (planId != null) {
                var ev = mapper.createObjectNode().put("event", "build");
                ev.put("scenarioId", scenario.scenarioId());
                ev.put("valid", ready);
                ev.set("purposeCheck", mapper.valueToTree(purposeCheck));
                ev.set("taskReadiness", mapper.valueToTree(taskReadiness));
                ev.set("evidenceChecks", mapper.valueToTree(evidenceChecks));
                ev.put("blocks", scenario.blocks().size());
                ev.put("backVerificationBlocks", scenario.backVerificationBlocks().size());
                ev.put("branchBlocks", scenario.branchBlocks().size());
                ev.set("droppedDefaults", dropped);
                ev.set("guardUnverified", args.has("userUtterances") ? mapper.valueToTree(guardUnverified) : null);
                planIdUnknown = rounds == null || !rounds.tryAttach(planId, ev);
            }
            if (ready) {
                store.putValidated(scenario, pes, args.path("request").asText(null), requestId, proof, fingerprint);
                store.linkPlan(scenario.scenarioId(), planIdUnknown ? null : planId);
            }

            var root = mapper.createObjectNode();
            root.put("sesId", pes.sesId());
            root.put("sesVersion", pes.sesVersion());
            root.put("scenarioId", scenario.scenarioId());
            root.put("valid", ready);
            root.put("requestId", requestId);
            root.put("requirementReady", requirementReady);
            root.set("requirementErrors", mapper.valueToTree(requirementReport.errors()));
            root.set("requirementChecks", mapper.valueToTree(requirementChecks));
            root.set("region", region);
            root.set("scenarioMode", proof.path("scenarioMode"));
            root.set("dataNotice", proof.path("dataNotice"));
            root.set("structureSelection", proof.path("structureSelection"));
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
            root.set("branchBlocks", mapper.valueToTree(scenario.branchBlocks()));
            root.set("unapprovedDefaults", mapper.valueToTree(pes.unapprovedDefaults()));
            root.set("decisionRoles", proof.get("decisionRoles"));
            if (!dropped.isEmpty()) root.set("droppedDefaults", dropped);
            if (args.has("userUtterances")) root.set("guardUnverified", mapper.valueToTree(guardUnverified));
            if (planIdUnknown) root.put("planIdUnknown", true);
            // 토큰은 여기서 나오지 않는다. 검증을 통과해도 아직 미확인이며,
            // 사람이 확인 화면에서 승인해야 발급된다(ScenarioConfirmController → ConfirmScenarioTool).
            root.put("state", store.entry(scenario.scenarioId())
                    .map(ScenarioStore.Entry::state).orElse("INVALID"));
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (IllegalArgumentException e) {
            return rejected(planId, "ERROR", null, ToolFailure.of("values", e.getMessage()));
        } catch (Exception e) {
            return rejected(planId, "ERROR", null,
                    ToolFailure.of("scenario", "시나리오 생성에 실패했습니다: " + e.getMessage()));
        }
    }

    /** 만들기 전에 거절해도 그 시도를 요청의 기록에 남긴다. 모르는 planId 면 tryAttach 가 붙이지 않는다. */
    private ToolResult rejected(String planId, String by, Integer guardBlocks, ToolResult failure) {
        if (planId != null && rounds != null) {
            var ev = mapper.createObjectNode().put("event", "build");
            ev.put("valid", false);
            ev.put("rejectedBy", by);
            if (guardBlocks != null) ev.put("guardBlocks", guardBlocks);
            rounds.tryAttach(planId, ev);
        }
        return failure;
    }

    /**
     * 확정된 값을 결정의 역할로 묶는다. 확인 화면이 "사용자가 정해야 하는데 기본값으로 채운 것"(intentDefaults)을
     * 따로 보여 주고, 지역 자료가 없어 기본값을 쓴 데이터 결정(dataGaps)과 모델 가정(assumptions)을 구분하게 한다.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode roleGroups(Pes pes) {
        var out = mapper.createObjectNode();
        for (String g : List.of("intentGiven", "intentDefaults", "dataFromRegion", "dataGaps", "assumptions")) out.putObject(g);
        pes.values().forEach((key, value) -> catalog.byAnswerKey(key).ifPresent(t -> {
            String origin = pes.origins().getOrDefault(key, "MODEL_DEFAULT");
            String group = switch (String.valueOf(t.decisionRole())) {
                case "INTENT" -> "MODEL_DEFAULT".equals(origin) ? "intentDefaults" : "intentGiven";
                case "DATA" -> "MODEL_DEFAULT".equals(origin) ? "dataGaps" : "dataFromRegion";
                default -> "assumptions";
            };
            ((com.fasterxml.jackson.databind.node.ObjectNode) out.get(group)).set(key, mapper.valueToTree(value));
        }));
        return out;
    }
}
