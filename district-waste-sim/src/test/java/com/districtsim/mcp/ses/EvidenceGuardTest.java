package com.districtsim.mcp.ses;

import com.districtsim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGuardTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private static final List<String> SAID = List.of("구역 8개, 오전 6시에 시작해서 4주 동안 봐줘. 반복은 3번");

    @Test
    void 시각과_주는_단위를_바꿔_보고_다른_숫자는_막는다() {
        var r = EvidenceGuard.check(
                Map.of("collectionStartMinutes", List.of(360), "days", List.of(28), "zoneCount", List.of(10)),
                Map.of("collectionStartMinutes", "USER", "days", "USER", "zoneCount", "USER"),
                Map.of("collectionStartMinutes", "오전 6시", "days", "4주", "zoneCount", "구역 8개"), SAID, catalog);
        assertThat(r.blocks()).singleElement().asString().contains("zoneCount");
    }

    @Test
    void 구역_배정_기본값은_동의_구절이_있어야_한다() {
        var r = EvidenceGuard.check(Map.of("zoneAllocation", List.of("EQUAL_COUNT")), Map.of(), Map.of(), SAID, catalog);
        assertThat(r.blocks()).singleElement().asString().contains("zoneAllocation");
    }
}
