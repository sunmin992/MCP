package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.pes.ScenarioBuilder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 실행 도구가 확인을 거쳤는지 판정한다.
 *
 * <p>{@code run_waste_simulation}·{@code run_scenario}·{@code update_route_sequence} 는
 * 서브태스크 흐름 <b>밖에서도</b> 쓰인다(REST 화면, 기존 시나리오 API). 토큰을 무조건
 * 요구하면 그 경로가 전부 막히므로 막지 않는다 — 대신 확인을 거쳤는지를 결과에 남긴다.
 * "무엇으로 계산한 값인가" 를 결과가 말하게 하는 것과 같은 방식이다.
 *
 * <p>다만 <b>틀린 토큰은 통과시키지 않는다.</b> 토큰을 줬다는 것은 확인을 주장한 것이고,
 * 틀린 주장을 통과시키면 확인 절차가 아무것도 보장하지 못한다. 없는 것과 틀린 것은 다르다.
 */
@Component
public class ExecutionConfirmation {

    /**
     * @param allowed    실행해도 되는가
     * @param confirmed  사용자 확인을 거친 실행인가
     * @param scenarioId 확인된 경우 그 시나리오. 아니면 빈 문자열
     * @param note       결과에 함께 실을 한 줄. 왜 확인됐는지 또는 왜 막혔는지
     */
    public record Check(boolean allowed, boolean confirmed, String scenarioId, String note) {}

    private static final String OUTSIDE =
            "서브태스크 흐름 밖에서 실행됐습니다 — 사용자가 이 설정을 확인하지 않았습니다.";

    private final ScenarioStore store;
    private final ScenarioBuilder builder;

    public ExecutionConfirmation(ScenarioStore store, ScenarioBuilder builder) {
        this.store = store;
        this.builder = builder;
    }

    public Check check(JsonNode args) {
        String token = args == null ? null : args.path("confirmToken").asText(null);
        if (token == null || token.isBlank()) {
            return new Check(true, false, "", OUTSIDE);
        }

        Optional<ScenarioStore.Entry> found = store.byToken(token);
        if (found.isEmpty()) {
            return new Check(false, false, "",
                    "확인 토큰이 보관된 시나리오와 맞지 않습니다 — 서버가 다시 떴거나 다른 서버의 토큰입니다.");
        }

        // 실행 직전에 다시 센다. 발급 이후 설정이 바뀌었으면 여기서 어긋난다.
        var scenario = found.get().scenario();
        if (!builder.tokenMatches(scenario, token)) {
            return new Check(false, false, scenario.scenarioId(),
                    "확인 토큰이 이 시나리오의 현재 설정과 맞지 않습니다 — 발급 이후 설정이 바뀌었습니다.");
        }
        return new Check(true, true, scenario.scenarioId(),
                "사용자가 확인한 설정입니다 (" + scenario.scenarioId() + ").");
    }
}
