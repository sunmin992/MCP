package com.wastesim.broker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2단계가 내는 것 — 발화에서 뽑은 조건을 <b>구조로</b> 받는다.
 *
 * <p>명세 규칙 1은 "2단계에서 추정하지 않는다"이다. 모르는 것을 빈 문자열이나 빈 목록으로
 * 뭉개면 그 규칙이 형식에서 사라진다 — 나중에 보는 사람이 "없다고 말한 것"과 "묻지 않아
 * 모르는 것"을 구분할 수 없다. 그래서 미제시는 {@code null}로 남긴다.
 */
class RequestProfileTest {

    @Test
    void 명세_2단계_예시가_그대로_들어간다() {
        RequestProfile p = new RequestProfile(
                "쓰레기수거", "한 동네", List.of("평일 교통량"),
                "민원이 가장 적은 수거 시각", List.of("수거 시각"));

        assertEquals("쓰레기수거", p.domain());
        assertEquals("한 동네", p.spatialScale());
        assertEquals(List.of("평일 교통량"), p.environmentConditions());
        assertEquals("민원이 가장 적은 수거 시각", p.objective());
    }

    @Test
    void 도메인이_없으면_거절한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RequestProfile("  ", "한 동네", null, null, null));
        assertTrue(e.getMessage().contains("도메인"),
                "도메인 없이는 후보를 거를 축이 하나도 없다: " + e.getMessage());
    }

    @Test
    void 미제시_항목은_null_로_남는다() {
        RequestProfile p = new RequestProfile("쓰레기수거", null, null, null, null);
        assertNull(p.spatialScale());
        assertNull(p.environmentConditions(), "빈 목록으로 바꾸면 '없다'와 '모른다'가 같아진다");
        assertNull(p.objective());
        assertNull(p.comparisonAxes());
    }

    @Test
    void 빈_문자열은_미제시로_본다() {
        RequestProfile p = new RequestProfile("쓰레기수거", "   ", null, "", null);
        assertNull(p.spatialScale(), "공백만 있는 값을 조건으로 들고 가면 매칭이 빈 문자열과 대조한다");
        assertNull(p.objective());
    }

    @Test
    void 빈_목록과_미제시는_다르다() {
        RequestProfile 없다고_말함 = new RequestProfile("쓰레기수거", null, List.of(), null, null);
        RequestProfile 모름 = new RequestProfile("쓰레기수거", null, null, null, null);
        assertNotNull(없다고_말함.environmentConditions());
        assertTrue(없다고_말함.environmentConditions().isEmpty());
        assertNull(모름.environmentConditions());
    }

    @Test
    void 목록은_밖에서_바꿀_수_없다() {
        List<String> 원본 = new java.util.ArrayList<>(List.of("평일 교통량"));
        RequestProfile p = new RequestProfile("쓰레기수거", null, 원본, null, null);
        원본.add("나중에 끼워넣기");
        assertEquals(List.of("평일 교통량"), p.environmentConditions(),
                "프로필이 만들어진 뒤 조건이 늘면 어느 조건으로 고른 것인지 말할 수 없다");
    }
}
