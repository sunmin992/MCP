package com.wastesim.evidence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 공통 근거 검사. 어느 시뮬레이터에도 묶이지 않음을 보이려고, 저장소에 없는 가상의 시내버스 시뮬레이터 필드로 검사한다.
 * 필드 이름은 일부러 기존 규칙(…Minutes, days, …Share)과 다르게 지었다 — 선언한 단위로만 판정해야 한다.
 */
class EvidenceGuardTest {
    static final Map<String, EvidenceField> BUS = Map.of(
            "busCount", new EvidenceField("busCount", "INTEGER", null, false, null, List.of("대")),
            "stopCount", new EvidenceField("stopCount", "INTEGER", null, false, null, List.of("곳", "개", "정류장")),
            "headway", new EvidenceField("headway", "INTEGER", "minute", false, null, null),
            "horizon", new EvidenceField("horizon", "INTEGER", "day", false, null, null),
            "fare", new EvidenceField("fare", "INTEGER", "won", false, null, null),
            "loadLimit", new EvidenceField("loadLimit", "NUMBER", "kg", false, null, null),
            "lowFloorShare", new EvidenceField("lowFloorShare", "NUMBER", "ratio", false, null, null),
            "busType", new EvidenceField("busType", "ENUM", null, false,
                    Map.of("ARTICULATED", List.of("굴절", "굴절버스"), "STANDARD", List.of("일반", "일반버스")), null),
            "priorityLane", new EvidenceField("priorityLane", "ENUM", null, false,
                    Map.of("APPLY", List.of("반영", "포함"), "IGNORE", List.of("반영하지", "빼고")), null),
            "transferWindow", new EvidenceField("transferWindow", "INTEGER", "minute", true, null, null));

    static EvidenceGuard.Result check(String key, Object value, String quote, String said) {
        return EvidenceGuard.check(Map.of(key, List.of(value)), Map.of(key, "USER"), Map.of(key, quote), List.of(said),
                k -> Optional.ofNullable(BUS.get(k)));
    }

    @Test void declaredUnitsConvertWithoutNameHeuristics() {
        assertTrue(check("headway", 90, "1시간 30분 간격", "배차는 1시간 30분 간격").blocks().isEmpty());
        assertTrue(check("horizon", 14, "2주", "2주 동안 돌려줘").blocks().isEmpty());
        assertTrue(check("fare", 15000, "1만 5천원", "요금 1만 5천원").blocks().size() <= 1, "복합 금액은 판단 못 해도 된다");
        assertTrue(check("fare", 20000, "2만원", "요금은 2만원").blocks().isEmpty());
        assertTrue(check("loadLimit", 1000, "1톤", "적재 한도 1톤").blocks().isEmpty());
        assertTrue(check("lowFloorShare", 0.3, "30%", "저상버스 30%").blocks().isEmpty());
        assertFalse(check("horizon", 2, "2주", "2주 동안 돌려줘").blocks().isEmpty(), "주를 일로 바꾸지 않은 값은 막는다");
    }

    @Test void numberSpokenWithAnotherFieldsCounterIsBlocked() {
        // "버스 3대" 를 정류장 수 3 의 근거로 쓴 경우
        var r = check("stopCount", 3, "버스 3대", "버스 3대로 정류장 12곳을 돌아줘");
        assertEquals(1, r.blocks().size());
        assertTrue(r.blocks().get(0).contains("다른 단위"));
        assertTrue(check("stopCount", 12, "정류장 12곳", "버스 3대로 정류장 12곳을 돌아줘").blocks().isEmpty());
        assertTrue(check("busCount", 3, "버스 3대", "버스 3대로 정류장 12곳을 돌아줘").blocks().isEmpty());
    }

    @Test void choiceTermsUseLongestMatchSoNegationWins() {
        assertTrue(check("busType", "ARTICULATED", "굴절버스로", "굴절버스로 해줘").blocks().isEmpty());
        assertFalse(check("busType", "STANDARD", "굴절버스로", "굴절버스로 해줘").blocks().isEmpty());
        assertTrue(check("priorityLane", "IGNORE", "전용차로 반영하지 말고", "전용차로 반영하지 말고 봐줘").blocks().isEmpty());
        assertFalse(check("priorityLane", "APPLY", "전용차로 반영하지 말고", "전용차로 반영하지 말고 봐줘").blocks().isEmpty());
        var unknown = check("busType", "STANDARD", "그냥 아무거나", "그냥 아무거나");
        assertTrue(unknown.blocks().isEmpty());
        assertEquals(List.of("busType"), unknown.unverified());
    }

    @Test void quoteMustComeFromTheUserAndConditionalDefaultsNeedConsent() {
        assertFalse(check("busCount", 30, "30대", "버스 3대").blocks().isEmpty());
        var noConsent = EvidenceGuard.check(Map.of("transferWindow", List.of(30)), Map.of(), Map.of(), List.of("버스 3대"),
                k -> Optional.ofNullable(BUS.get(k)));
        assertEquals(1, noConsent.blocks().size());
        var consent = EvidenceGuard.check(Map.of("transferWindow", List.of(30)), Map.of(), Map.of("transferWindow", "기본값으로"),
                List.of("환승은 기본값으로 해줘"), k -> Optional.ofNullable(BUS.get(k)));
        assertTrue(consent.blocks().isEmpty());
    }
}
