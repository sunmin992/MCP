package com.wastesim.evidence;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class KoreanNumbersTest {
    static String n(String s) { return KoreanNumbers.normalize(s); }

    @Test void dayWords() {
        assertEquals("2일 동안 돌려줘", n("이틀 동안 돌려줘"));
        assertEquals("3일간", n("사흘간"));
        assertEquals("10일, 15일", n("열흘, 보름"));
        assertEquals("1일에 2번", n("하루에 두 번"));
        assertEquals("1주 동안", n("일주일 동안"));
    }

    @Test void nativeNumbersWithCounters() {
        assertEquals("차량 2대", n("차량 두 대"));
        assertEquals("차 2대로", n("차 두대로"));
        assertEquals("창구 12곳", n("창구 열두 곳"));
        assertEquals("20명", n("스무 명"));
        assertEquals("1시간", n("한 시간"));
        assertEquals("3회 반복", n("세 회 반복"));
        assertEquals("4시에 수거", n("네 시에 수거"));
        assertEquals("2번째", n("두 번째"));
    }

    @Test void wordsThatOnlyLookLikeNumbersStay() {
        assertEquals("동당 세대 수", n("동당 세대 수"));
        assertEquals("한동안 기다려", n("한동안 기다려"));
        assertEquals("두부 한 모", n("두부 한 모"));
        assertEquals("이레네", n("이레네"));
        assertEquals("보름달", n("보름달"));
        assertEquals("열심히", n("열심히"));
        assertEquals("3대", n("세 대"), "띄어 쓴 세 대는 차량 세 대");
    }

    @Test void halvesMoneyAndClockWords() {
        assertEquals("1.5시간마다", n("한 시간 반마다"));
        assertEquals("2.5시간", n("2시간 반"));
        assertEquals("요금 15000원", n("요금 1만 5천원"));
        assertEquals("2만원", n("2만원"));
        assertEquals("낮 12시에", n("정오에"));
        assertEquals("밤 12시", n("자정"));
    }

    @Test void idempotent() {
        for (String s : List.of("이틀 동안 차량 두 대로 한 시간 반마다", "요금 1만 5천원, 정오", "동당 세대 수"))
            assertEquals(n(s), n(n(s)), s);
    }

    @Test void clockWordsConvertToMinutesOfDay() {
        assertTrue(UnitEvidence.explains(10, 1320, "밤 10시에 수거", "minute"));
        assertTrue(UnitEvidence.explains(6, 360, "새벽 6시", "minute"));
        assertTrue(UnitEvidence.explains(7, 1140, "저녁 7시", "minute"));
        assertTrue(UnitEvidence.explains(12, 0, "자정", "minute"));
        assertTrue(UnitEvidence.explains(12, 720, "정오", "minute"));
        assertTrue(UnitEvidence.explains(1, 90, "한 시간 반 간격", "minute"));
    }

    @Test void guardAcceptsSpokenKoreanNumbers() {
        var fields = Map.of(
                "days", new EvidenceField("days", "INTEGER", "day", false, null, null),
                "truckCount", new EvidenceField("truckCount", "INTEGER", null, false, null, List.of("대")),
                "numBuildings", new EvidenceField("numBuildings", "INTEGER", null, false, null, List.of("동")),
                "collectionTimeMinutes", new EvidenceField("collectionTimeMinutes", "INTEGER", "minute", false, null, null));
        String said = "이틀 동안 차량 두 대로 밤 10시에 수거해줘";
        var r = EvidenceGuard.check(
                Map.of("days", List.of(2), "truckCount", List.of(2), "collectionTimeMinutes", List.of(1320)),
                Map.of("days", "USER", "truckCount", "USER", "collectionTimeMinutes", "USER"),
                Map.of("days", "이틀 동안", "truckCount", "차량 두 대로", "collectionTimeMinutes", "밤 10시에"),
                List.of(said), k -> Optional.ofNullable(fields.get(k)));
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
        assertTrue(r.unverified().isEmpty(), "이틀 · 두 대도 이제 숫자로 확인된다: " + r.unverified());
        // 차량 "두 대" 를 건물 수의 근거로 쓰면 여전히 다른 단위로 막는다
        var wrong = EvidenceGuard.check(Map.of("numBuildings", List.of(2)), Map.of("numBuildings", "USER"),
                Map.of("numBuildings", "차량 두 대로"), List.of(said), k -> Optional.ofNullable(fields.get(k)));
        assertEquals(1, wrong.blocks().size());
    }
}
