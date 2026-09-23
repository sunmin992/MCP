package com.wastesim.mcp.ses;

import com.wastesim.pes.Scenario;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 검증을 마친 시나리오를 확인·실행 단계까지 보관한다.
 *
 * <p>세 상태를 토큰 하나와 실행 시각 하나로 가른다 — 별도 상태 필드를 두면 토큰과
 * 어긋날 수 있고, 어긋났을 때 어느 쪽이 정본인지 알 수 없다.
 *
 * <ul>
 *   <li>토큰 없음 → {@code UNCONFIRMED} — 돌릴 수 있지만 사용자가 확인하지 않았다</li>
 *   <li>토큰 있음, 실행 시각 없음 → {@code CONFIRMED} — 확인했으나 아직 돌리지 않았다</li>
 *   <li>실행 시각 있음 → {@code EXECUTED}</li>
 * </ul>
 *
 * <p>메모리에만 둔다 — 서버가 다시 뜨면 토큰도 함께 사라지는 것이 맞다. 재기동 뒤에도
 * 살아 있는 토큰은 그 사이 바뀐 리소스로 실행될 수 있다.
 */
@Component
public class ScenarioStore {

    /** @param executedAt 실행한 시각. 아직 돌리지 않았으면 {@code null} */
    public record Entry(Scenario scenario, Instant executedAt) {

        public String state() {
            if (executedAt != null) return "EXECUTED";
            return scenario.confirmToken() == null ? "UNCONFIRMED" : "CONFIRMED";
        }
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();

    public String put(Scenario scenario) {
        byId.put(scenario.scenarioId(), new Entry(scenario, null));
        return scenario.scenarioId();
    }

    public Optional<Entry> entry(String scenarioId) {
        return Optional.ofNullable(byId.get(scenarioId));
    }

    public Optional<Scenario> get(String scenarioId) {
        return entry(scenarioId).map(Entry::scenario);
    }

    /** 확인된 시나리오로 갈아 끼운다. 실행 이력은 지운다 — 다시 확인했으면 다시 돌릴 수 있다. */
    public void confirm(Scenario confirmed) {
        byId.put(confirmed.scenarioId(), new Entry(confirmed, null));
    }

    public void markExecuted(String scenarioId) {
        byId.computeIfPresent(scenarioId, (k, e) -> new Entry(e.scenario(), Instant.now()));
    }
}
