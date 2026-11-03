package com.wastesim.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 브로커 공통 계약 요구의 근거 검사. 도메인에 묶이지 않도록 폐기물 · 주차 · 버스 요청을 섞어 본다. */
class RequirementEvidenceTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static JsonNode j(String s) throws Exception { return M.readTree(s); }

    @Test void faithfulRequirementsPass() throws Exception {
        var r = RequirementEvidence.check(j("""
            {"regionId":"상계동","simulationDuration":{"value":2,"unit":"week"},"requiredOutputs":["meanComplaints"],
             "constraints":{"numTrucks":3},
             "evidence":{"regionId":"상계동에서","simulationDuration":"2주 동안","requiredOutputs":"민원 수 보여줘","constraints.numTrucks":"차량 3대"}}
            """), List.of("상계동에서 차량 3대로 2주 동안 돌려서 민원 수 보여줘"));
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
        assertEquals(List.of("requiredOutputs"), r.unverified(), "지표를 어떤 이름으로 옮겼는지는 판단하지 못한다");
    }

    @Test void inventedOrMistranslatedRequirementsAreBlocked() throws Exception {
        String said = "이틀 동안 망원동 주차장 시뮬레이션 해줘";
        // 말하지 않은 기간 단위를 바꿈(2일 → 2주)
        assertFalse(RequirementEvidence.check(j("""
            {"simulationDuration":{"value":2,"unit":"week"},"evidence":{"simulationDuration":"2일"}}"""), List.of("2일 동안")).blocks().isEmpty());
        // 말하지 않은 수치 조건을 지어냄
        assertFalse(RequirementEvidence.check(j("""
            {"constraints":{"lotCount":5},"evidence":{"lotCount":"주차장 5곳"}}"""), List.of(said)).blocks().isEmpty());
        // 근거 없이 요구를 넣음
        assertFalse(RequirementEvidence.check(j("""
            {"regionId":"망원동"}"""), List.of(said)).blocks().isEmpty());
        // 구절에 없는 지역명
        assertFalse(RequirementEvidence.check(j("""
            {"regionId":"합정동","evidence":{"regionId":"망원동"}}"""), List.of(said)).blocks().isEmpty());
        // 숫자 없는 표현은 판단 보류
        var vague = RequirementEvidence.check(j("""
            {"simulationDuration":{"value":2,"unit":"day"},"evidence":{"simulationDuration":"이틀 동안"}}"""), List.of(said));
        assertTrue(vague.blocks().isEmpty());
        assertEquals(List.of("simulationDuration"), vague.unverified());
    }

    @Test void numericConstraintsAcceptKnownUnitConversions() throws Exception {
        var r = RequirementEvidence.check(j("""
            {"constraints":{"threshold":0.7,"headwayMinutes":90},"evidence":{"threshold":"70% 넘으면","headwayMinutes":"1시간 30분마다"}}"""),
                List.of("적재율 70% 넘으면 민원으로 보고 버스는 1시간 30분마다"));
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
    }
}
