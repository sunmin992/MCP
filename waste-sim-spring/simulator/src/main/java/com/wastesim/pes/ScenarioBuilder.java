package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
import com.wastesim.template.SubtaskInstance;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.SubtaskStatus;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationError;
import com.wastesim.tool.ValidationResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PES + 실험 프레임 → 검증된 실행 설정 N벌 + 확인 토큰.
 *
 * <p>범위 검증은 {@link SimulationConfigValidator}에 위임한다. 새 검증기가 범위를 다시
 * 정의하면 두 곳이 갈라지고, 갈라진 뒤에는 어느 쪽이 정본인지 알 수 없다.
 *
 * <p><b>서브태스크 완결은 서버가 다시 센다.</b> 실행 설정마다(실험 변수 값을 넣은 PES 로) 생성 조건을
 * 평가해, 물어야 할 결정이 비어 있거나 판정이 보류된 채면 막는다. LLM 이 {@code plan_subtasks} 의
 * {@code complete} 를 건너뛰고 불러도 빠진 값이 실행 설정의 초기값으로 조용히 돌지 않게 하기 위해서다.
 * 실행 설정마다 따로 세는 이유는, 실험 변수에 따라 생기는 결정이 다르기 때문이다(차량 1대와 3대).
 */
@Component
public class ScenarioBuilder {

    private final PesFlattener flattener;
    private final PesBackVerifier backVerifier;
    private final SimulationConfigValidator validator;
    private final SubtaskPlanner planner;

    public ScenarioBuilder(PesFlattener flattener,
                           PesBackVerifier backVerifier,
                           SimulationConfigValidator validator,
                           SubtaskPlanner planner) {
        this.flattener = flattener;
        this.backVerifier = backVerifier;
        this.validator = validator;
        this.planner = planner;
    }

    public Scenario build(Pes pes, ExperimentFrame frame) {
        List<SimulationConfig> runs = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> backBlocks = new ArrayList<>();

        for (Object value : frame.values()) {
            Map<String, Object> values = new LinkedHashMap<>(pes.values());
            values.put(frame.variableAnswerKey(), value);
            // 실험 변수의 출처는 실험 프레임이다 — 따로 적혀 오지 않았으면 사용자가 정한 비교로
            // 본다. LLM 이 제안한 값(MODEL_DEFAULT)이면 그 출처를 지킨다. 삽입 순서를 유지해야
            // 같은 입력이 같은 시나리오 식별자를 낸다 — HashMap 을 쓰면 순서가 흔들린다.
            Map<String, String> origins = new LinkedHashMap<>(pes.origins());
            origins.putIfAbsent(frame.variableAnswerKey(), "USER");
            Pes runPes = new Pes(pes.sesId(), pes.sesVersion(), values, origins);

            // 물어야 할 결정을 먼저, 보류된 결정을 뒤에 적는다. 보류는 다른 값이 정해져야 필요한지 알 수
            // 있다는 뜻이라, 섞어 적으면 LLM 이 필요 없을 수도 있는 질문을 먼저 한다.
            String run = frame.variableAnswerKey() + "=" + value + ": ";
            List<SubtaskInstance> instances = planner.plan(values).instances();
            for (SubtaskInstance i : instances) {
                if (i.status() == SubtaskStatus.UNFILLED) {
                    // 기본값이 없는 결정에 "기본값을 받아들였으면" 이라고 쓰면, LLM 이 질문 문구의 예시 값을
                    // 기본값처럼 넣는다(2026-10-19 재실행 R10). 없으면 없다고, 있으면 그 값을 적는다.
                    Object dflt = planner.template(i.answerKey()).map(t -> t.defaultValue()).orElse(null);
                    String how = dflt == null
                            ? " (기본값이 없습니다 — 사용자에게 물어 값을 받으십시오. 질문의 예시 값을 대신 넣지 마십시오)"
                            : " (사용자가 기본값 " + dflt + " 을(를) 받아들였으면 그 값을 MODEL_DEFAULT 출처로 넣으십시오)";
                    blocks.add(run + "물어야 할 결정 " + i.answerKey() + " — " + i.question() + how);
                }
            }
            for (SubtaskInstance i : instances) {
                if (i.status() == SubtaskStatus.DEFERRED) {
                    blocks.add(run + "보류된 결정 " + i.answerKey() + " — "
                            + String.join(", ", planner.waitingOn(i.answerKey(), values))
                            + " 이(가) 정해져야 필요한지 알 수 있습니다. 먼저 정한 뒤 plan_subtasks 로 다시 확인하십시오");
                }
            }

            SimulationConfig cfg = flattener.flatten(runPes);
            runs.add(cfg);

            ValidationResult result = validator.validate(cfg);
            if (!result.ready()) {
                for (ValidationError e : result.errors()) {
                    blocks.add(frame.variableAnswerKey() + "=" + value + ": " + e.message());
                }
            }
            backBlocks.addAll(backVerifier.verify(runPes, cfg));
        }

        // 실험 프레임까지 넣어야 식별자가 된다. 값을 빼면 같은 PES 에 조건만 다른
        // 시나리오 둘이 같은 id 를 갖고 보관소에서 서로를 덮는다.
        String scenarioId = "scn-" + Integer.toHexString(
                Objects.hash(pes.values(), frame.variableAnswerKey(), frame.values()));

        // 토큰은 여기서 주지 않는다. 검증 통과는 "돌릴 수 있다" 이지 "사용자가 확인했다"가
        // 아니다. 확인 단계(confirm)가 발급하며, 그래서 토큰 없음 = 미확인이 된다.
        return new Scenario(scenarioId, runs, blocks, backBlocks, null);
    }

    /** 이 시나리오의 현재 설정에 대한 확인 토큰. 확인 단계만 부른다. */
    public String tokenFor(Scenario scenario) {
        return "cft-" + hashOf(scenario.runs());
    }

    /** 토큰이 이 시나리오의 현재 설정과 맞는가. 설정이 바뀌면 맞지 않는다. */
    public boolean tokenMatches(Scenario scenario, String token) {
        if (token == null || scenario.confirmToken() == null) return false;
        return scenario.confirmToken().equals(token)
                && token.equals("cft-" + hashOf(scenario.runs()));
    }

    /**
     * 이 설정이 시나리오가 확인받은 설정 중 하나인가. 기존 실행 도구는 설정을 따로 들고
     * 오므로, 토큰이 맞아도 그 설정이 확인받은 것인지는 여기서 따로 대조해야 한다.
     */
    public boolean isConfirmedRun(Scenario scenario, SimulationConfig cfg) {
        String h = hashOf(List.of(cfg));
        return scenario.runs().stream().anyMatch(r -> h.equals(hashOf(List.of(r))));
    }

    /**
     * 실행 설정 전체의 해시.
     *
     * <p>{@code SimulationConfig}의 문자열 표현을 쓰지 않는 이유는 그것이 재정의돼 있지
     * 않으면 객체 신원이 섞이기 때문이다. 게터를 이름순으로 훑어 값만 모은다.
     */
    private String hashOf(List<SimulationConfig> runs) {
        StringBuilder sb = new StringBuilder();
        List<Method> getters = Arrays.stream(SimulationConfig.class.getMethods())
                .filter(m -> m.getParameterCount() == 0)
                .filter(m -> m.getName().startsWith("get") || m.getName().startsWith("is"))
                .filter(m -> m.getDeclaringClass() == SimulationConfig.class)
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        for (SimulationConfig cfg : runs) {
            for (Method m : getters) {
                sb.append(m.getName()).append('=');
                try {
                    Object v = m.invoke(cfg);
                    sb.append(v instanceof double[] d ? Arrays.toString(d) : v);
                } catch (ReflectiveOperationException e) {
                    sb.append("<읽기실패>");
                }
                sb.append(';');
            }
            sb.append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }
}
