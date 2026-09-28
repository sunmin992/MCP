package com.wastesim.mcp.ses;

import com.wastesim.pes.Pes;
import com.wastesim.pes.Scenario;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
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

    /**
     * @param pes        이 시나리오를 만든 PES. 확인 화면이 "승인하지 않은 기본값" 을
     *                   보여주려면 필요하다 — 그것을 못 보여주면 확인이 확인이 아니다
     * @param executedAt 실행한 시각. 아직 돌리지 않았으면 {@code null}
     */
    public record Entry(Scenario scenario, Pes pes, Instant executedAt) {

        public String state() {
            if (executedAt != null) return "EXECUTED";
            return scenario.confirmToken() == null ? "UNCONFIRMED" : "CONFIRMED";
        }
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();

    public String put(Scenario scenario, Pes pes) {
        byId.put(scenario.scenarioId(), new Entry(scenario, pes, null));
        return scenario.scenarioId();
    }

    /** 보관 중인 전부. 확인 화면이 목록을 세운다. */
    public List<Entry> all() {
        return List.copyOf(byId.values());
    }

    /**
     * 토큰으로 찾는다. 토큰은 설정 해시라 사실상 유일하므로 시나리오 id 를 따로 받지 않는다 —
     * 기존 실행 도구는 시나리오 id 를 모르는 채 토큰만 들고 온다.
     */
    public Optional<Entry> byToken(String confirmToken) {
        if (confirmToken == null || confirmToken.isBlank()) return Optional.empty();
        return byId.values().stream()
                .filter(e -> confirmToken.equals(e.scenario().confirmToken()))
                .findFirst();
    }

    public Optional<Entry> entry(String scenarioId) {
        return Optional.ofNullable(byId.get(scenarioId));
    }

    public Optional<Scenario> get(String scenarioId) {
        return entry(scenarioId).map(Entry::scenario);
    }

    /** 확인된 시나리오로 갈아 끼운다. 실행 이력은 지운다 — 다시 확인했으면 다시 돌릴 수 있다. */
    public void confirm(Scenario confirmed) {
        byId.computeIfPresent(confirmed.scenarioId(),
                (k, e) -> new Entry(confirmed, e.pes(), null));
    }

    public void markExecuted(String scenarioId) {
        byId.computeIfPresent(scenarioId,
                (k, e) -> new Entry(e.scenario(), e.pes(), Instant.now()));
    }
}
