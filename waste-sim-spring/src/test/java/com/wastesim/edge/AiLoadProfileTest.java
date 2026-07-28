package com.wastesim.edge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiLoadProfileTest {

    private final AiLoadProfileService service = new AiLoadProfileService();

    /** Pi5 + 방열판(passive)의 시정수 — 설계 문서 §4.2. 패턴 설계의 기준값이다. */
    private static final double TAU_PI5_PASSIVE = 58.5;
    /** Pi5 무냉각(bare)의 시정수 — 방열판 유/무 비교 시 τ가 달라지는 것을 확인용. */
    private static final double TAU_PI5_BARE = 97.5;

    @Test
    void loadsAllThreeSeedProfiles() {
        assertEquals(3, service.all().size());
        assertNotNull(service.find("steady"));
        assertNotNull(service.find("burst"));
        assertNotNull(service.find("mixed"));
    }

    @Test
    void unknownProfileReturnsNullSoCallerFallsBackToConstantLoad() {
        assertNull(service.find("없는패턴"));
        assertNull(service.find(null));
    }

    @Test
    void defaultProfileIsTheControlGroup() {
        assertEquals("steady", service.defaultProfile().getId());
        assertTrue(service.defaultProfile().isConstant());
    }

    // ── 부하 배율 조회 ────────────────────────────────────────────────────

    @Test
    void burstAlternatesBetweenLevels() {
        AiLoadProfile burst = service.find("burst");
        assertEquals(300, burst.cycleSeconds(), 1e-9);   // 120 + 180

        assertEquals(1.0, burst.levelAt(0), 1e-9);
        assertEquals(1.0, burst.levelAt(119), 1e-9);
        assertEquals(0.2, burst.levelAt(120), 1e-9);     // 경계에서 다음 구간으로
        assertEquals(0.2, burst.levelAt(299), 1e-9);
    }

    @Test
    void patternRepeatsAfterOneCycle() {
        AiLoadProfile burst = service.find("burst");
        assertEquals(burst.levelAt(30), burst.levelAt(330), 1e-9);
        assertEquals(burst.levelAt(200), burst.levelAt(500), 1e-9);
        // 음수 시각도 감싸서 처리(적분기가 t<0으로 조회해도 터지지 않아야 한다)
        assertEquals(burst.levelAt(280), burst.levelAt(-20), 1e-9);
    }

    @Test
    void steadyHoldsOneLevelForever() {
        AiLoadProfile steady = service.find("steady");
        assertEquals(1.0, steady.levelAt(0), 1e-9);
        assertEquals(1.0, steady.levelAt(99999), 1e-9);
    }

    @Test
    void mixedHasSlowEnvelopeOverFastBursts() {
        AiLoadProfile mixed = service.find("mixed");
        assertEquals(1800, mixed.cycleSeconds(), 1e-9);   // 30분 주기
        assertFalse(mixed.isConstant());
        // 한가대 → 피크대로 갈수록 부하 수준이 올라간다
        assertEquals(0.50, mixed.levelAt(0), 1e-9);       // 한가대 몰림
        assertEquals(0.80, mixed.levelAt(600), 1e-9);     // 보통대 몰림
        assertEquals(1.00, mixed.levelAt(1200), 1e-9);    // 피크대 몰림
    }

    @Test
    void meanAndPeakAreComputedByDuration() {
        AiLoadProfile burst = service.find("burst");
        // (1.0×120 + 0.2×180) / 300 = 0.52
        assertEquals(0.52, burst.meanLevel(), 1e-9);
        assertEquals(1.0, burst.peakLevel(), 1e-9);

        // 대조군은 평균과 피크가 같다 — 같은 피크인데 평균이 다른 점이
        // 패턴 실험의 핵심(같은 최대 부하, 다른 누적 발열)
        AiLoadProfile steady = service.find("steady");
        assertEquals(steady.peakLevel(), steady.meanLevel(), 1e-9);
    }

    // ── 시간 규모 판정 (이 실험 설계의 핵심 가드) ──────────────────────────

    /** 패턴 실험이 의미를 가지려면 버스트·혼합이 실제 τ에서 SENSITIVE여야 한다.
     *  이게 깨지면 순위 변화 가설 자체를 검증할 수 없으므로 회귀로 고정한다. */
    @Test
    void burstAndMixedAreTransientSensitiveAtRealTimeConstants() {
        for (double tau : new double[]{TAU_PI5_PASSIVE, TAU_PI5_BARE}) {
            assertEquals(AiLoadProfile.TimescaleFit.SENSITIVE,
                    service.find("burst").timescaleFit(tau),
                    "burst가 τ=" + tau + "에서 과도응답을 드러내야 한다");
            assertEquals(AiLoadProfile.TimescaleFit.SENSITIVE,
                    service.find("mixed").timescaleFit(tau),
                    "mixed가 τ=" + tau + "에서 과도응답을 드러내야 한다");
        }
    }

    @Test
    void constantLoadIsAlwaysQuasiStatic() {
        assertEquals(AiLoadProfile.TimescaleFit.QUASI_STATIC,
                service.find("steady").timescaleFit(TAU_PI5_PASSIVE));
    }

    /** 일주기(시간 단위) 패턴을 쓰면 준정상상태가 되어 대조군과 결과가 같아진다 —
     *  실험이 실패하는 대표 사례라 명시적으로 고정해 둔다. */
    @Test
    void hourScalePatternDegeneratesToQuasiStatic() {
        AiLoadProfile diurnal = new AiLoadProfile();
        diurnal.setSegments(java.util.List.of(
                new AiLoadProfile.Segment(3600, 1.0, "낮"),
                new AiLoadProfile.Segment(3600, 0.2, "밤")));
        assertEquals(AiLoadProfile.TimescaleFit.QUASI_STATIC,
                diurnal.timescaleFit(TAU_PI5_PASSIVE));
        assertTrue(diurnal.timescaleNote(TAU_PI5_PASSIVE).contains("준정상상태"));
    }

    /** 반대쪽 실패 — 너무 잘게 쪼개도 열용량이 평균해버려 차이가 안 드러난다. */
    @Test
    void subSecondPatternDegeneratesToAveraged() {
        AiLoadProfile chatter = new AiLoadProfile();
        chatter.setSegments(java.util.List.of(
                new AiLoadProfile.Segment(2, 1.0, "on"),
                new AiLoadProfile.Segment(3, 0.2, "off")));
        assertEquals(AiLoadProfile.TimescaleFit.AVERAGED,
                chatter.timescaleFit(TAU_PI5_PASSIVE));
    }

    @Test
    void timescaleNoteExplainsEachVerdict() {
        assertTrue(service.find("burst").timescaleNote(TAU_PI5_PASSIVE).contains("과도응답"));
        assertTrue(service.find("steady").timescaleNote(TAU_PI5_PASSIVE).contains("대조군"));
    }

    // ── 방어 ────────────────────────────────────────────────────────────

    @Test
    void emptyProfileFallsBackToFullLoad() {
        AiLoadProfile empty = new AiLoadProfile();
        assertEquals(1.0, empty.levelAt(0), 1e-9);
        assertEquals(1.0, empty.meanLevel(), 1e-9);
        assertTrue(empty.isConstant());
    }

    @Test
    void levelIsClampedToValidRange() {
        AiLoadProfile weird = new AiLoadProfile();
        weird.setSegments(java.util.List.of(
                new AiLoadProfile.Segment(60, 1.8, "과대"),
                new AiLoadProfile.Segment(60, -0.5, "음수")));
        assertEquals(1.0, weird.levelAt(0), 1e-9);
        assertEquals(0.0, weird.levelAt(60), 1e-9);
    }
}
