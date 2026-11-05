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
        // 한국어 수 표현은 숫자로 바꿔 확인한다
        var spoken = RequirementEvidence.check(j("""
            {"simulationDuration":{"value":2,"unit":"day"},"evidence":{"simulationDuration":"이틀 동안"}}"""), List.of(said));
        assertTrue(spoken.blocks().isEmpty());
        assertTrue(spoken.unverified().isEmpty(), spoken.unverified().toString());
        assertFalse(RequirementEvidence.check(j("""
            {"simulationDuration":{"value":3,"unit":"day"},"evidence":{"simulationDuration":"이틀 동안"}}"""), List.of(said)).blocks().isEmpty(),
            "이틀을 3일로 옮기면 막는다");
        // 수가 없는 구절로 수를 넣으면 막는다 — 오류 주입(2026-11-05)에서 발화 전체를 구절로 대고 기간을 지어낸 경우가 통과했다
        assertFalse(RequirementEvidence.check(j("""
            {"simulationDuration":{"value":3,"unit":"day"},"evidence":{"simulationDuration":"며칠 동안"}}"""), List.of("며칠 동안 돌려줘")).blocks().isEmpty());
        assertFalse(RequirementEvidence.check(j("""
            {"simulationDuration":{"value":1,"unit":"week"},"evidence":{"simulationDuration":"평균 운행 소요 시간을 보고 싶어"}}"""),
                List.of("평균 운행 소요 시간을 보고 싶어")).blocks().isEmpty());
        assertFalse(RequirementEvidence.check(j("""
            {"constraints":[{"name":"차량","value":3,"unit":"대"}],"evidence":{"constraints.차량":"차량을 넉넉히"}}"""),
                List.of("차량을 넉넉히 배치해줘")).blocks().isEmpty());
    }

    @Test void preferenceAndRequirementMustMatchTheWording() throws Exception {
        String soft = "쓰레기 수거 시뮬레이션은 가능하면 10회 반복해줘", hard = "쓰레기 수거 시뮬레이션을 반드시 101회 반복해줘";
        var keptSoft = RequirementEvidence.check(j("""
            {"preferences":[{"name":"반복","value":10,"unit":"회"}],"evidence":{"preferences.반복":"가능하면 10회 반복해줘"}}"""), List.of(soft));
        assertTrue(keptSoft.blocks().isEmpty(), keptSoft.blocks().toString());
        assertFalse(RequirementEvidence.check(j("""
            {"constraints":[{"name":"반복","value":10,"unit":"회"}],"evidence":{"constraints.반복":"가능하면 10회 반복해줘"}}"""), List.of(soft)).blocks().isEmpty(),
            "선호를 필수로 올리면 막는다");
        assertFalse(RequirementEvidence.check(j("""
            {"preferences":[{"name":"반복","value":101,"unit":"회"}],"evidence":{"preferences.반복":"반드시 101회 반복해줘"}}"""), List.of(hard)).blocks().isEmpty(),
            "필수를 선호로 내리면 카드 범위를 넘는 요청도 통과하므로 막는다");
        assertFalse(RequirementEvidence.check(j("""
            {"preferences":[{"name":"건물","value":27,"unit":"개"}],"evidence":{"preferences.건물":"건물 27개인 블록"}}"""),
                List.of("건물 27개인 블록의 쓰레기 수거를 실행해줘")).blocks().isEmpty(), "표지 없는 조건은 필수다");
    }

    @Test void numericConstraintsAcceptKnownUnitConversions() throws Exception {
        var r = RequirementEvidence.check(j("""
            {"constraints":{"threshold":0.7,"headwayMinutes":90},"evidence":{"threshold":"70% 넘으면","headwayMinutes":"1시간 30분마다"}}"""),
                List.of("적재율 70% 넘으면 민원으로 보고 버스는 1시간 30분마다"));
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
    }
}
