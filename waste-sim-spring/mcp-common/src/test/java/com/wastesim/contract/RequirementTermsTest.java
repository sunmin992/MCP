package com.wastesim.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 사용자 표현 → 제공자 이름 대응. 저장소에 없는 가상의 버스 시뮬레이터 카드로 시뮬레이터에 묶이지 않음을 본다. */
class RequirementTermsTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static JsonNode j(String s) throws Exception { return M.readTree(s); }

    static final String BUS = """
        {"serverId":"bus-sim","unsupported":[{"capability":"요금 수입","matchKeys":["수입","매출","metric:revenue"]}],
         "capabilities":{"outputs":["waitMean","waitStd","loadFactor"],
           "constraints":{"headway":{"min":1,"max":120},"busCount":{"min":1,"max":50},"lowFloorShare":{"min":0,"max":1}},
           "terms":{
             "outputs":{"waitMean":["평균 대기 시간","대기 시간"],"waitStd":["대기 시간 편차"],"loadFactor":["재차율","혼잡도"]},
             "constraints":{"headway":{"terms":["배차 간격"],"unit":"minute"},
                            "busCount":{"terms":["버스 대수","버스"],"unitWords":["대"]},
                            "lowFloorShare":{"terms":["저상버스 비율","저상버스"],"unit":"ratio"}},
             "examples":[{"output":"평균 대기 시간","expect":"waitMean"},
                         {"constraint":{"name":"배차 간격","value":1,"unit":"시간"},"expect":{"headway":60}}]}}}
        """;

    @Test void userPhrasesMapToProviderNamesByLongestTerm() throws Exception {
        var r = RequirementTerms.resolve(j("""
            {"requiredOutputs":["평균 대기 시간","대기 시간 편차","혼잡도"],
             "constraints":[{"name":"배차 간격","value":1,"unit":"시간"},{"name":"버스","value":12,"unit":"대"},
                            {"name":"저상버스 비율","value":30,"unit":"%"}]}"""), j(BUS));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(j("[\"waitMean\",\"waitStd\",\"loadFactor\"]"), r.requirements().get("requiredOutputs"));
        assertEquals(60, r.requirements().path("constraints").path("headway").asInt());
        assertEquals(12, r.requirements().path("constraints").path("busCount").asInt());
        assertEquals(0.3, r.requirements().path("constraints").path("lowFloorShare").asDouble(), 1e-9);
    }

    @Test void unmatchedUnsupportedAndWrongUnitStayAsIssues() throws Exception {
        var r = RequirementTerms.resolve(j("""
            {"requiredOutputs":["요금 수입","탄소 배출량"],"constraints":[{"name":"버스","value":3,"unit":"분"}]}"""), j(BUS));
        var codes = r.issues().stream().map(c -> c.field() + "=" + c.code()).toList();
        assertEquals(List.of("requiredOutputs.요금 수입=TERM_UNSUPPORTED", "requiredOutputs.탄소 배출량=TERM_UNMATCHED",
                "constraints.버스=UNIT_MISMATCH"), codes);
        assertEquals(0, r.requirements().path("requiredOutputs").size());
    }

    @Test void legacyProviderNamesStillWork() throws Exception {
        var r = RequirementTerms.resolve(j("""
            {"requiredOutputs":["waitMean"],"constraints":{"headway":10}}"""), j(BUS));
        assertTrue(r.issues().isEmpty());
        assertEquals(10, r.requirements().path("constraints").path("headway").asInt());
    }

    @Test void evaluateReportsUnmatchedPhrasesInsteadOfDroppingThem() throws Exception {
        var checks = RequirementContract.evaluate(j("""
            {"requiredOutputs":["평균 대기 시간","탄소 배출량"]}"""), j(BUS));
        assertTrue(checks.stream().anyMatch(c -> c.field().equals("requiredOutputs.waitMean") && c.status().equals("SATISFIED")));
        assertTrue(checks.stream().anyMatch(c -> c.code().equals("TERM_UNMATCHED")));
    }

    @Test void cardExamplesAreCheckedWhenTheCardIsValidated() throws Exception {
        RequirementContract.validateCard(j(BUS));
        String broken = BUS.replace("\"expect\":{\"headway\":60}", "\"expect\":{\"headway\":1}");
        var e = assertThrows(IllegalArgumentException.class, () -> RequirementContract.validateCard(j(broken)));
        assertTrue(e.getMessage().contains("예문"));
        String unknownMetric = BUS.replace("\"loadFactor\":[\"재차율\"", "\"ridership\":[\"재차율\"");
        assertThrows(IllegalArgumentException.class, () -> RequirementContract.validateCard(j(unknownMetric)));
    }
}
