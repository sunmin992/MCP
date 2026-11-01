package com.parkingsim.mcp.ses;

import com.parkingsim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGuardTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private static final List<String> SAID = List.of(
            "주차장 4곳에 각각 80면, 14일을 3회 반복해줘. 요금은 시간대 차등으로 하고 할증은 30%야.",
            "탐색은 15분까지 하고 포기해.",
            "갱신 주기는 기본값으로 해줘.");

    private EvidenceGuard.Result check(Map<String, List<Object>> submitted, Map<String, String> origins,
                                       Map<String, String> evidence) {
        return EvidenceGuard.check(submitted, origins, evidence, SAID, catalog);
    }

    @Test
    void 사용자가_말한_숫자와_같으면_통과한다() {
        var r = check(Map.of("seeds", List.of(3), "maxSearchMinutes", List.of(15), "peakSurchargeRate", List.of(0.3)),
                Map.of("seeds", "USER", "maxSearchMinutes", "USER", "peakSurchargeRate", "USER"),
                Map.of("seeds", "3회 반복", "maxSearchMinutes", "15분까지", "peakSurchargeRate", "할증은 30%"));
        assertThat(r.blocks()).isEmpty();
    }

    @Test
    void 삼회를_삼십으로_내면_막는다() {
        var r = check(Map.of("seeds", List.of(30)), Map.of("seeds", "USER"), Map.of("seeds", "3회 반복"));
        assertThat(r.blocks()).singleElement().asString().contains("seeds").contains("다릅니다");
    }

    @Test
    void 발화에_없는_구절이나_빈_근거는_막는다() {
        var r = check(Map.of("lotCount", List.of(12), "days", List.of(14)),
                Map.of("lotCount", "USER", "days", "USER"),
                Map.of("lotCount", "주차장 12곳"));
        assertThat(r.blocks()).hasSize(2);
        assertThat(String.join(" ", r.blocks())).contains("발화에 없습니다").contains("evidence)이 없습니다");
    }

    @Test
    void 말투만_바꾼_구절은_통과하고_지어낸_숫자는_막는다() {
        List<String> said = List.of("1톤 차량을 1대 쓸 때와 3대 쓸 때를 비교해줘. 14일 3회.");
        var paraphrased = EvidenceGuard.check(Map.of("lotCount", List.of(1, 3)), Map.of("lotCount", "USER"),
                Map.of("lotCount", "1대와 3대를 비교해 주세요"), said, catalog);
        assertThat(paraphrased.blocks()).isEmpty();

        var invented = EvidenceGuard.check(Map.of("seeds", List.of(30)), Map.of("seeds", "USER"),
                Map.of("seeds", "30회 반복"), said, catalog);
        assertThat(invented.blocks()).singleElement().asString()
                .contains("발화에 없습니다").contains("사용자에게 물으십시오");

        // 짧은 구절은 다른 항목의 숫자와 글자쌍 하나만 겹쳐도 통과하면 안 된다
        var borrowed = EvidenceGuard.check(Map.of("seeds", List.of(30)), Map.of("seeds", "USER"),
                Map.of("seeds", "30회"), List.of("30분당 500원 균일요금, 14일 3회."), catalog);
        assertThat(borrowed.blocks()).singleElement().asString().contains("발화에 없습니다");
    }

    @Test
    void 실험_변수는_값마다_본다() {
        var r = check(Map.of("lotCount", List.of(4, 6)), Map.of("lotCount", "USER"), Map.of("lotCount", "주차장 4곳"));
        assertThat(r.blocks()).singleElement().asString().contains("6");
    }

    @Test
    void 숫자가_없는_구절과_열거형은_판단하지_못한_것으로_남긴다() {
        var r = check(Map.of("pricingPolicy", List.of("TIME_OF_DAY")), Map.of("pricingPolicy", "USER"),
                Map.of("pricingPolicy", "시간대 차등"));
        assertThat(r.blocks()).isEmpty();
        assertThat(r.unverified()).containsExactly("pricingPolicy");
    }

    @Test
    void 조건부_결정의_기본값은_동의_구절이_있어야_한다() {
        var none = check(Map.of("congestionProfileId", List.of("weekday-downtown")), Map.of(), Map.of());
        assertThat(none.blocks()).singleElement().asString().contains("동의받지 않은 기본값");

        var agreed = check(Map.of("guidanceRefreshMinutes", List.of(5)),
                Map.of("guidanceRefreshMinutes", "MODEL_DEFAULT"), Map.of("guidanceRefreshMinutes", "기본값으로 해줘"));
        assertThat(agreed.blocks()).isEmpty();
    }

    @Test
    void 항상_묻는_결정의_기본값은_막지_않는다() {
        var r = check(Map.of("dailyArrivals", List.of(900)), Map.of("dailyArrivals", "MODEL_DEFAULT"), Map.of());
        assertThat(r.blocks()).isEmpty();
    }

    @Test
    void 시각은_분으로_바꿔_본다() {
        var r = EvidenceGuard.check(Map.of("lotSpacingMinutes", List.of(120.0)), Map.of("lotSpacingMinutes", "USER"),
                Map.of("lotSpacingMinutes", "2시간"), List.of("이동은 2시간 걸려"), catalog);
        assertThat(r.blocks()).isEmpty();
    }
}
