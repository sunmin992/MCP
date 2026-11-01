package com.wastesim.mcp.ses;

import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGuardTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private static final List<String> SAID = List.of(
            "차량 3대로 오후 6시에 수거하고 30일을 3회 반복해줘", "배차 간격은 기본값으로");

    @Test
    void 삼회를_삼십으로_내면_막고_오후_시각은_분으로_본다() {
        var r = EvidenceGuard.check(
                Map.of("seeds", List.of(30), "collectionTimeMinutes", List.of(1080), "truckCount", List.of(3)),
                Map.of("seeds", "USER", "collectionTimeMinutes", "USER", "truckCount", "USER"),
                Map.of("seeds", "3회 반복", "collectionTimeMinutes", "오후 6시", "truckCount", "차량 3대"), SAID, catalog);
        assertThat(r.blocks()).singleElement().asString().contains("seeds");
    }

    @Test
    void 배차_간격_기본값은_동의_구절이_있어야_한다() {
        var none = EvidenceGuard.check(Map.of("dispatchIntervalMinutes", List.of(0)), Map.of(), Map.of(), SAID, catalog);
        assertThat(none.blocks()).singleElement().asString().contains("동의받지 않은 기본값");
        var agreed = EvidenceGuard.check(Map.of("dispatchIntervalMinutes", List.of(0)),
                Map.of("dispatchIntervalMinutes", "MODEL_DEFAULT"), Map.of("dispatchIntervalMinutes", "기본값으로"), SAID, catalog);
        assertThat(agreed.blocks()).isEmpty();
    }
}
