package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.tool.ConfigArgs;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 실행 도구가 확인을 거쳤는지 판정한다.
 *
 * <p>{@code run_waste_simulation}·{@code run_scenario}·{@code update_route_sequence} 도
 * <b>토큰 없이는 돌지 않는다.</b> 예전에는 "확인 안 됨" 표시만 붙여 통과시켰는데, 그러면
 * MCP 클라이언트가 승인 절차를 건너뛰는 지름길이 된다(사용자 요청 시험 B11). REST 화면은
 * MCP 를 거치지 않고 {@code SimulationTool} 을 직접 부르므로 이 검사에 막히지 않는다.
 *
 * <p>틀린 토큰도 통과시키지 않는다. 토큰을 줬다는 것은 확인을 주장한 것이고,
 * 틀린 주장을 통과시키면 확인 절차가 아무것도 보장하지 못한다.
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

    private static final String NO_TOKEN =
            "확인 토큰이 없습니다 — 사용자가 확인 화면에서 승인한 설정만 실행합니다. "
                    + "plan_subtasks → build_scenario 로 시나리오를 만들고, 사용자가 확인한 뒤 "
                    + "run_scenario_by_token 으로 실행하십시오.";

    private final ScenarioStore store;
    private final ScenarioBuilder builder;

    public ExecutionConfirmation(ScenarioStore store, ScenarioBuilder builder) {
        this.store = store;
        this.builder = builder;
    }

    public Check check(JsonNode args) {
        String token = args == null ? null : args.path("confirmToken").asText(null);
        if (token == null || token.isBlank()) {
            return new Check(false, false, "", NO_TOKEN);
        }

        Optional<ScenarioStore.Entry> found = store.byToken(token);
        if (found.isEmpty()) {
            return new Check(false, false, "",
                    "확인 토큰이 보관된 시나리오와 맞지 않습니다 — 서버가 다시 떴거나 다른 서버의 토큰입니다.");
        }

        String id = found.get().scenario().scenarioId();
        if (!store.validationCurrent(id))
            return new Check(false, false, id, "입력·데이터·목적이 바뀌어 이전 완료 판정과 승인이 무효입니다. 시나리오를 다시 생성하십시오.");
        if (store.validation(id).path("purposeCheck").path("specification").isObject())
            return new Check(false, false, id, "목적과 비교 결과를 검증한 시나리오는 run_scenario_by_token으로 전체 실행해야 합니다.");

        // 확인 한 번에 실행 한 번이다. 여기서 보지 않으면 쓴 토큰을 기존 도구로 다시 쓸 수 있다.
        if (found.get().executedAt() != null) {
            return new Check(false, false, found.get().scenario().scenarioId(),
                    "이미 실행한 시나리오의 토큰입니다 — 다시 돌리려면 사용자가 확인 화면에서 다시 확인해야 합니다.");
        }

        // 실행 직전에 다시 센다. 발급 이후 설정이 바뀌었으면 여기서 어긋난다.
        var scenario = found.get().scenario();
        if (!builder.tokenMatches(scenario, token)) {
            return new Check(false, false, scenario.scenarioId(),
                    "확인 토큰이 이 시나리오의 현재 설정과 맞지 않습니다 — 발급 이후 설정이 바뀌었습니다.");
        }

        // 토큰은 시나리오가 확인됐다는 뜻이지, 지금 넘어온 설정이 확인됐다는 뜻이 아니다.
        // 대조하지 않으면 확인하지 않은 설정이 "사용자가 확인한 설정" 으로 표시된다.
        if (!builder.isConfirmedRun(scenario, ConfigArgs.fromJson(args))) {
            return new Check(false, false, scenario.scenarioId(),
                    "이 설정은 확인 토큰의 시나리오(" + scenario.scenarioId()
                            + ")에서 사용자가 확인한 설정이 아닙니다 — 확인한 설정 그대로 보내거나 토큰을 빼십시오.");
        }
        return new Check(true, true, scenario.scenarioId(),
                "사용자가 확인한 설정입니다 (" + scenario.scenarioId() + ").");
    }
}
