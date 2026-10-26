package com.parkingsim.mcp.ses;

import com.parkingsim.pes.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioStoreTest {

    private final ScenarioStore store = new ScenarioStore();

    private ScenarioStore.Entry confirmed(String id) {
        store.put(new Scenario(id, List.of(), List.of(), List.of(), null), null);
        store.confirm(new Scenario(id, List.of(), List.of(), List.of(), "cft-" + id));
        return store.entry(id).orElseThrow();
    }

    @Test
    void 같은_시나리오의_실행권은_한_요청만_얻는다() {
        // 토큰 검사와 실행 사이에 같은 토큰의 다른 요청이 끼면 한 번의 승인으로 두 번 돈다.
        ScenarioStore.Entry e = confirmed("scn-a");
        assertThat(store.beginExecution("scn-a", e)).isTrue();
        assertThat(store.beginExecution("scn-a", e)).isFalse();
    }

    @Test
    void 실행_중에는_다시_확인할_수_없다() {
        ScenarioStore.Entry e = confirmed("scn-b");
        store.beginExecution("scn-b", e);
        assertThat(store.confirm(new Scenario("scn-b", List.of(), List.of(), List.of(), "cft-scn-b"))).isFalse();
        store.endExecution("scn-b");
        assertThat(store.confirm(new Scenario("scn-b", List.of(), List.of(), List.of(), "cft-scn-b"))).isTrue();
    }

    @Test
    void 확인하지_않았거나_실행한_시나리오는_실행권을_얻지_못한다() {
        store.put(new Scenario("scn-c", List.of(), List.of(), List.of(), null), null);
        assertThat(store.beginExecution("scn-c", store.entry("scn-c").orElseThrow())).isFalse();

        ScenarioStore.Entry e = confirmed("scn-d");
        store.markExecuted("scn-d");
        assertThat(store.beginExecution("scn-d", e)).isFalse();
        assertThat(store.beginExecution("scn-d", store.entry("scn-d").orElseThrow())).isFalse();
    }
}
