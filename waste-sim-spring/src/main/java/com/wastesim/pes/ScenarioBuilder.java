package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
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
 */
@Component
public class ScenarioBuilder {

    private final PesFlattener flattener;
    private final PesBackVerifier backVerifier;
    private final SimulationConfigValidator validator;

    public ScenarioBuilder(PesFlattener flattener,
                           PesBackVerifier backVerifier,
                           SimulationConfigValidator validator) {
        this.flattener = flattener;
        this.backVerifier = backVerifier;
        this.validator = validator;
    }

    public Scenario build(Pes pes, ExperimentFrame frame) {
        List<SimulationConfig> runs = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> backBlocks = new ArrayList<>();

        for (Object value : frame.values()) {
            Map<String, Object> values = new LinkedHashMap<>(pes.values());
            values.put(frame.variableAnswerKey(), value);
            // 실험 변수의 출처는 실험 프레임이다. 삽입 순서를 유지해야 같은 입력이
            // 같은 시나리오 식별자를 낸다 — HashMap 을 쓰면 순서가 흔들린다.
            Map<String, String> origins = new LinkedHashMap<>(pes.origins());
            origins.put(frame.variableAnswerKey(), "USER");
            Pes runPes = new Pes(pes.sesId(), pes.sesVersion(), values, origins);

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
