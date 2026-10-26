package com.countersim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.countersim.engine.CounterExperiment;
import com.countersim.mcp.McpToolProvider;
import com.countersim.mcp.ToolFailure;
import com.countersim.model.CounterConfig;
import com.countersim.pes.Pes;
import com.countersim.pes.Scenario;
import com.countersim.pes.ScenarioBuilder;
import com.countersim.template.SubtaskInstance;
import com.countersim.template.SubtaskPlanner;
import com.countersim.template.SubtaskStatus;
import com.countersim.template.SubtaskTemplate;
import com.countersim.template.TemplateCatalog;
import com.countersim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 확인 토큰이 맞는 시나리오만 실행한다.
 *
 * <p>실행 직전에 보관된 설정을 다시 해싱해 토큰과 대조한다. 발급 이후 설정이 바뀌었으면 옛 토큰은
 * 무효다. 확인 한 번에 실행 한 번이다.
 *
 * <p>결과는 능력 카드 {@code resultFields} 의 네 분류(판정 · 설명 · 참고 · 신뢰성)로 묶어 낸다.
 * 신뢰성에는 <b>사용자가 말하지 않은 값</b>을 적는다 — 기본값으로 승인된 값, 그리고 PES 에 아예
 * 없어서 실행 설정 초기값으로 돈 값. 뒤의 것은 확인 화면에도 나오지 않으므로 결과가 스스로 말해야 한다.
 */
@Component
public class RunScenarioByTokenTool implements McpToolProvider {

    private final ScenarioBuilder builder;
    private final ScenarioStore store;
    private final CounterExperiment experiment;
    private final SubtaskPlanner planner;
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;

    public RunScenarioByTokenTool(ScenarioBuilder builder, ScenarioStore store, CounterExperiment experiment,
                                  SubtaskPlanner planner, TemplateCatalog catalog, ObjectMapper mapper) {
        this.builder = builder;
        this.store = store;
        this.experiment = experiment;
        this.planner = planner;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "run_scenario_by_token"; }

    @Override
    public String description() {
        return "확인 토큰이 맞는 시나리오를 실행한다. 토큰이 없거나 발급 이후 설정이 바뀌었으면 실행하지 않는다. "
                + "실행 설정마다 반복 횟수(seeds)만큼 돌려 평균을 낸다 — 판정: 평균 대기 시간 · 일평균 이탈 · "
                + "예약/방문 유형별 대기, 설명: 창구 가동률 · 시간대별 대기 · 최대 대기 인원 · 초과 근무, "
                + "참고: 창구당 처리 건수, 신뢰성: 방문객 수지 · 가정 메모. 결과는 설정 간 비교이며 운영 예측이 아니다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "scenarioId":{"type":"string","description":"build_scenario 가 돌려준 시나리오 id"},
               "confirmToken":{"type":"string","description":"사용자가 확인 화면에서 승인한 뒤 get_scenario_status 로 읽은 확인 토큰"},
               "seed":{"type":"integer","description":"쓰이지 않는다 — 반복 횟수는 시나리오의 seeds 가 정하고 시드는 1..seeds 로 고정이다"}},
             "required":["scenarioId","confirmToken"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String scenarioId = args.path("scenarioId").asText(null);
        String token = args.path("confirmToken").asText(null);
        if (scenarioId == null || scenarioId.isBlank()) {
            return ToolFailure.of("scenarioId", "시나리오 id 가 없습니다.");
        }
        if (token == null || token.isBlank()) {
            return ToolFailure.of("confirmToken", "확인 토큰이 없습니다 — 확인하지 않은 설정은 실행하지 않습니다.");
        }
        Optional<ScenarioStore.Entry> found = store.entry(scenarioId);
        if (found.isEmpty()) {
            return ToolFailure.of("scenarioId", "보관된 시나리오가 없습니다: " + scenarioId
                    + " — build_scenario 로 다시 만드십시오. 서버가 다시 뜨면 토큰도 함께 사라집니다.");
        }
        if (found.get().executedAt() != null) {
            return ToolFailure.of("confirmToken", "이미 실행한 시나리오입니다: " + scenarioId
                    + " — 다시 돌리려면 사용자가 확인 화면에서 다시 확인해야 합니다.");
        }
        Scenario scenario = found.get().scenario();
        if (scenario.confirmToken() == null) {
            return ToolFailure.of("confirmToken", "아직 확인되지 않은 시나리오입니다: " + scenarioId
                    + " — 사용자에게 확인 화면에서 설정을 승인해 달라고 안내하십시오. 화면 주소(confirmUrl)는"
                    + " get_scenario_status 가 알려 주고, 승인 뒤 토큰도 거기서 읽습니다.");
        }
        if (!builder.tokenMatches(scenario, token)) {
            return ToolFailure.of("confirmToken", "확인 토큰이 이 시나리오의 현재 설정과 맞지 않습니다 — "
                    + "발급 이후 설정이 바뀌었거나 다른 시나리오의 토큰입니다.");
        }
        // 위 검사와 실행 사이에 같은 토큰의 다른 요청이 끼면 두 번 돈다. 실행권을 한 요청에만 준다.
        if (!store.beginExecution(scenarioId, found.get())) {
            return ToolFailure.of("confirmToken",
                    "이미 실행 중이거나 시나리오 상태가 바뀌었습니다. 상태를 다시 확인하십시오.");
        }
        try {
            Pes pes = found.get().pes();
            List<String> swept = sweptGetters(scenario.runs());

            var runs = mapper.createArrayNode();
            for (CounterConfig cfg : scenario.runs()) {
                List<String> notes = assumptionNotes(pes, cfg, swept);
                CounterExperiment.Summary s = experiment.run(cfg);
                ObjectNode node = runs.addObject();
                ObjectNode settings = node.putObject("settings");
                for (String g : swept) settings.set(property(g), mapper.valueToTree(read(cfg, g)));

                ObjectNode judgement = node.putObject("judgement");
                judgement.put("meanWaitMinutesAll", s.meanWaitMinutesAll());
                judgement.put("stdWaitMinutesAll", s.stdWaitMinutesAll());
                judgement.put("meanWaitMinutesServed", s.meanWaitMinutesServed());
                judgement.put("renegesPerDay", s.renegesPerDay());
                judgement.put("stdRenegesPerDay", s.stdRenegesPerDay());
                judgement.put("renegeRate", s.renegeRate());
                judgement.set("byVisitorType", mapper.valueToTree(s.byVisitorType()));

                ObjectNode explanation = node.putObject("explanation");
                explanation.put("utilization", s.utilization());
                explanation.set("hourlyMeanWait", mapper.valueToTree(s.hourlyMeanWait()));
                explanation.put("peakQueueLength", s.peakQueueLength());
                explanation.put("overtimeMinutesPerDay", s.overtimeMinutesPerDay());

                ObjectNode reference = node.putObject("reference");
                reference.put("servedPerCounterPerDay", s.servedPerCounterPerDay());

                ObjectNode integrity = node.putObject("integrity");
                integrity.put("seeds", s.seeds());
                integrity.put("visitorBalanceErrorMax", s.visitorBalanceErrorMax());
                integrity.set("allReneges", mapper.valueToTree(s.allReneges()));
                integrity.set("dataQualityFlags", mapper.valueToTree(List.of("DEMAND_ASSUMED", "SERVICE_TIME_ASSUMED")));
                integrity.set("assumptionNotes", mapper.valueToTree(notes));
            }
            store.markExecuted(scenarioId);

            var root = mapper.createObjectNode();
            root.put("scenarioId", scenario.scenarioId());
            root.put("state", store.entry(scenarioId).map(ScenarioStore.Entry::state).orElse("EXECUTED"));
            root.put("notForOperationalUse", true);
            root.put("limitation", "이 결과는 설정 간 비교이며 운영 예측이 아니다. 시간대별 방문 분포 · "
                    + "처리 시간 분포 · 대기 한도는 가정값이다.");
            root.set("runs", runs);
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("scenario", "실행에 실패했습니다: " + e.getMessage());
        } finally {
            store.endExecution(scenarioId);
        }
    }

    /**
     * 사용자가 말하지 않은 값 — 기본값으로 승인된 값과, PES 에 없어 초기값으로 돈 값.
     *
     * <p>실험 변수는 PES 에 들어 있지 않다(값이 여럿이라). 이 실행의 변수 값을 넣고 생성 조건을 평가해야
     * 한다 — 빼면 변수 자체와 변수에 걸린 결정이 "빠졌다" 고 잘못 나온다.
     */
    private List<String> assumptionNotes(Pes pes, CounterConfig cfg, List<String> swept) {
        List<String> notes = new ArrayList<>();
        if (!pes.unapprovedDefaults().isEmpty()) {
            notes.add("사용자가 말하지 않고 기본값으로 정해 확인 화면에서 승인된 값: " + pes.unapprovedDefaults());
        }
        Map<String, Object> answers = new LinkedHashMap<>(pes.values());
        for (SubtaskTemplate t : catalog.all()) {
            // 변수에 출처를 적으면 보관 PES 에 비교 값 목록이 실린다. 이미 있어도 이 실행의 값으로 덮어써야
            // 생성 조건을 판정한다 — 목록 그대로 두면 변수에 걸린 결정이 보류되어 "빠졌다" 고 나온다.
            String getter = swept.stream().filter(g -> property(g).equals(t.configField())).findFirst().orElse(null);
            if (getter == null) continue;
            Object v = read(cfg, getter);
            // 답변값과 설정값이 다른 유일한 칸 — 예약제(APPLY/IGNORE ↔ 참/거짓).
            answers.put(t.answerKey(), v instanceof Boolean b ? (b ? "APPLY" : "IGNORE") : v);
        }
        List<String> missing = planner.plan(answers).instances().stream()
                .filter(i -> i.status() == SubtaskStatus.UNFILLED || i.status() == SubtaskStatus.DEFERRED)
                .map(SubtaskInstance::answerKey)
                .toList();
        if (!missing.isEmpty()) {
            notes.add("PES 에 없어 실행 설정 초기값으로 실행된 결정: " + missing
                    + " — 확인 화면에 보이지 않았던 값이다.");
        }
        return notes;
    }

    /** 실행마다 값이 달라지는 게터 — 실험 변수다. 서버가 따로 들고 있을 필요가 없다. */
    private static List<String> sweptGetters(List<CounterConfig> runs) {
        List<String> out = new ArrayList<>();
        if (runs.size() < 2) return out;
        for (Method m : getters()) {
            Object first = invoke(m, runs.get(0));
            if (runs.stream().anyMatch(r -> !Objects.equals(invoke(m, r), first))) out.add(m.getName());
        }
        return out;
    }

    private static List<Method> getters() {
        return Arrays.stream(CounterConfig.class.getMethods())
                .filter(m -> m.getParameterCount() == 0)
                .filter(m -> m.getName().startsWith("get") || m.getName().startsWith("is"))
                .filter(m -> m.getDeclaringClass() == CounterConfig.class)
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /** 게터 이름 → 설정 필드 이름. {@code getQueueDiscipline} → {@code queueDiscipline}. */
    private static String property(String getter) {
        String rest = getter.startsWith("is") ? getter.substring(2) : getter.substring(3);
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static Object read(CounterConfig cfg, String getter) {
        try {
            return invoke(CounterConfig.class.getMethod(getter), cfg);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method m, CounterConfig cfg) {
        try {
            return m.invoke(cfg);
        } catch (ReflectiveOperationException e) {
            return "<읽기실패>";
        }
    }
}
