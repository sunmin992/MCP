package com.wastesim.edge;

import com.wastesim.tool.ErrorCode;

/**
 * 엣지 도구 3종이 공유하는 인자 해석 로직. 도구마다 스키마 문구는 다르지만
 * "보드·주변온도·부하·회복 정책·열 파라미터 덮어쓰기"를 읽는 방식은 같아야
 * 결과를 서로 비교할 수 있다.
 */
final class EdgeToolSupport {

    private EdgeToolSupport() {}

    static final String BOARD_ENUM = "pi4, pi5";
    static final String COOLING_ENUM = "bare, passive, active";
    static final String MODE_ENUM = "target_fps, max_throughput";
    static final String POLICY_ENUM = "r1_stop, r2_low_load, r3_active_cooling, none";

    /**
     * 열 파라미터를 만든다. 우선순위는 <b>실측 &gt; 명시 덮어쓰기 &gt; 프리셋</b>이다.
     * <ol>
     *   <li>{@code profileId} — 캘리브레이션으로 저장된 실측 파라미터(가장 신뢰도 높음)</li>
     *   <li>{@code thermalOverride} — 학생이 직접 넣은 값</li>
     *   <li>보드·냉각 프리셋 기본값(문헌 추정치)</li>
     * </ol>
     */
    static ThermalParams thermalParams(EdgeArgs a, BoardType board, CoolingPreset cooling,
                                       double ambientC, EdgeThermalProfileStore store) {
        ThermalParams p = ThermalParams.preset(board, cooling, ambientC);

        String profileId = a.str("profileId", null);
        if (profileId != null && !profileId.isBlank()) {
            EdgeThermalProfileStore.Profile prof = store == null ? null : store.get(profileId);
            if (prof == null) {
                a.reject(ErrorCode.INVALID_ARGUMENTS, "profileId",
                        "저장된 캘리브레이션 프로파일이 없다: " + profileId
                                + " (calibrate_edge_thermal_model을 먼저 실행해 얻은 id를 넣을 것)");
            } else {
                p = applyOverride(p, prof.override());
            }
        }
        if (a.has("thermalOverride")) {
            EdgeArgs o = a.child("thermalOverride");
            double rJa = o.dbl("rJaKPerW", p.rJaKPerW(), 0.1, 100.0);
            double cTh = o.dbl("cThJPerK", p.cThJPerK(), 0.5, 500.0);
            double amb = o.dbl("ambientC", p.ambientC(), -20.0, 60.0);
            double idle = o.dbl("idlePowerW", p.idlePowerW(), 0.0, 50.0);
            double dyn = o.dbl("dynamicPowerW", p.dynamicPowerW(), 0.0, 100.0);
            p = new ThermalParams(amb, rJa, cTh, idle, dyn, p.maxClockMhz(), p.softFloorClockMhz(), p.minClockMhz(),
                    p.softLimitC(), p.hardLimitC(), p.hysteresisC(), p.maxFps(), amb + idle * rJa);
            p = p.withStartTemp(o.dbl("startTempC", p.startTempC(), -20.0, 110.0));
        }

        // 실측 프로파일에는 측정 당시 주변 온도가 박혀 있다. 호출자가 ambientTempC를 명시했다면
        // 그쪽이 이긴다 — "같은 보드를 다른 실온에서 돌리면?"이 이 도구의 주 사용법이기 때문이다.
        if (a.has("ambientTempC") && Math.abs(p.ambientC() - ambientC) > 1e-9) {
            p = p.withAmbient(ambientC);
        }

        double maxFps = a.dbl("maxFps", p.maxFps(), 0.1, 1000.0);
        p = p.withMaxFps(maxFps);
        double startTemp = a.dbl("startTempC", p.startTempC(), -20.0, 110.0);
        return p.withStartTemp(startTemp);
    }

    private static ThermalParams applyOverride(ThermalParams base, ThermalCalibrator.ThermalOverride o) {
        return new ThermalParams(o.ambientC(), o.rJaKPerW(), o.cThJPerK(), o.idlePowerW(),
                o.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(), base.minClockMhz(),
                base.softLimitC(), base.hardLimitC(), base.hysteresisC(), base.maxFps(), o.startTempC());
    }

    /**
     * 부하·회복 명세를 읽는다. 회복 열저항은 R3일 때만 팬 조건으로 낮춘다.
     *
     * <p>{@code defaultMode}가 도구마다 다른 이유: 발열 시뮬레이션은 "실제 서비스처럼 목표
     * FPS를 유지할 때"가 기본 질문이지만, 방열판 배치 비교는 <b>최악 조건에서 어느 배치가
     * 버티는가</b>가 기본 질문이라 최대 처리량으로 놓아야 후보 간 차이가 드러난다.
     * 각 도구의 JSON Schema에 적힌 default와 반드시 같아야 한다.
     */
    static ThermalSimulator.Spec spec(EdgeArgs a, BoardType board, ThermalParams p,
                                      double defaultLoadSec, WorkloadMode defaultMode) {
        WorkloadMode mode = a.enumVal("workloadMode", defaultMode, WorkloadMode::parse, MODE_ENUM, false);
        double targetFps = a.dbl("targetFps", Math.min(10.0, p.maxFps()), 0.1, 1000.0);
        double loadSec = a.dbl("loadSeconds", defaultLoadSec, 10.0, 21600.0);
        RecoveryPolicy policy = a.enumVal("recoveryPolicy", RecoveryPolicy.NONE,
                RecoveryPolicy::parse, POLICY_ENUM, false);
        double recoverySec = a.dbl("recoverySeconds", policy == RecoveryPolicy.NONE ? 0.0 : 600.0,
                0.0, 21600.0);

        double defaultRecoveryRJa = policy == RecoveryPolicy.R3_ACTIVE_COOLING
                ? Math.min(p.rJaKPerW(), board.rJaKPerW(CoolingPreset.ACTIVE))
                : p.rJaKPerW();
        double recoveryRJa = a.dbl("recoveryRJaKPerW", defaultRecoveryRJa, 0.1, 100.0);

        double sampleSec = a.dbl("sampleIntervalSeconds", 5.0, 0.5, 300.0);
        boolean onThrottle = a.bool("applyRecoveryOnThrottle", true);
        // dt는 시정수보다 훨씬 작아야 적분 오차가 무시된다 — τ/50과 0.5초 중 작은 값, 하한 0.05초.
        double dt = Math.max(0.05, Math.min(0.5, p.tauSeconds() / 50.0));
        return new ThermalSimulator.Spec(p, mode, targetFps, loadSec, policy, recoverySec,
                recoveryRJa, dt, sampleSec, onThrottle);
    }

    /** 방열판 후보 하나를 읽는다. 형상·배치·기류·TIM 전부 범위 검증한다. */
    static HeatsinkLayout layout(EdgeArgs a, String fallbackName) {
        String name = a.str("name", fallbackName);

        EdgeArgs h = a.child("heatsink");
        double baseL = h.reqDbl("baseLengthMm", 3.0, 200.0);
        double baseW = h.reqDbl("baseWidthMm", 3.0, 200.0);
        double baseT = h.dbl("baseThicknessMm", 2.0, 0.2, 20.0);
        int finCount = h.intVal("finCount", 0, 0, 200);
        double finH = h.dbl("finHeightMm", finCount > 0 ? 10.0 : 0.0, 0.0, 100.0);
        double finT = h.dbl("finThicknessMm", finCount > 0 ? 1.0 : 0.0, 0.0, 20.0);
        HeatsinkLayout.Material mat = h.enumVal("material", HeatsinkLayout.Material.ALUMINUM,
                HeatsinkLayout.Material::parse, "aluminum, copper", false);
        if (finCount > 0 && finH <= 0) {
            h.reject(ErrorCode.INVALID_ARGUMENTS, "finHeightMm", "핀이 있는데 높이가 0이다.");
        }

        EdgeArgs pl = a.child("placement");
        double offX = pl.dbl("offsetXMm", 0.0, -100.0, 100.0);
        double offY = pl.dbl("offsetYMm", 0.0, -100.0, 100.0);
        HeatsinkLayout.FinAlignment align = pl.enumVal("finAlignment", HeatsinkLayout.FinAlignment.ALIGNED,
                HeatsinkLayout.FinAlignment::parse, "aligned, cross", false);

        EdgeArgs af = a.child("airflow");
        HeatsinkLayout.AirflowType type = af.enumVal("type", HeatsinkLayout.AirflowType.NATURAL,
                HeatsinkLayout.AirflowType::parse, "natural, forced", false);
        double speed = af.dbl("airSpeedMps", 0.0, 0.0, 20.0);
        double rpm = af.dbl("fanRpm", 0.0, 0.0, 20000.0);
        double dist = af.dbl("fanDistanceMm", 5.0, 0.0, 300.0);

        EdgeArgs tm = a.child("tim");
        HeatsinkLayout.TimType timType = tm.enumVal("type", HeatsinkLayout.TimType.PAD,
                HeatsinkLayout.TimType::parse, "pad, paste, tape", false);
        double timThick = tm.dbl("thicknessMm", timType.defaultThicknessMm(), 0.01, 5.0);
        double timK = tm.dbl("conductivityWmK", 0.0, 0.0, 500.0);

        java.util.List<HeatsinkLayout.Hotspot> spots = new java.util.ArrayList<>();
        var arr = a.raw("hotspots");
        if (arr.isArray()) {
            int i = 0;
            for (var n : arr) {
                EdgeArgs s = new EdgeArgs(n);
                spots.add(new HeatsinkLayout.Hotspot(
                        s.str("name", "hotspot" + (++i)),
                        s.dbl("xMm", 0.0, -200.0, 200.0),
                        s.dbl("yMm", 0.0, -200.0, 200.0),
                        s.dbl("powerW", 0.5, 0.0, 50.0)));
                a.errors().addAll(s.errors());
            }
        }

        return new HeatsinkLayout(name,
                new HeatsinkLayout.Heatsink(baseL, baseW, baseT, finCount, finH, finT, mat),
                new HeatsinkLayout.Placement(offX, offY, align),
                new HeatsinkLayout.Airflow(type, speed, rpm, dist),
                new HeatsinkLayout.Tim(timType, timThick, timK),
                spots);
    }
}
