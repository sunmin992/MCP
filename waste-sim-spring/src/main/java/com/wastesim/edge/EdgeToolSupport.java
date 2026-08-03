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
        return spec(a, board, p, defaultLoadSec, defaultMode, null);
    }

    /**
     * 냉각팬을 읽는다. {@code fanRpm}이 없으면 null(팬 없음).
     *
     * <p>회전수를 주면 열저항이 그에 맞춰 낮아지고 소비전력이 집계된다 — 팬을 "공짜로
     * 시원하게 해 주는 장치"가 아니라 <b>전력을 쓰는 대가로 냉각을 사는 요소</b>로 다뤄야
     * "적정 수준에서 멈추는 것이 낫다"는 결론이 계산으로 나온다.
     */
    static FanSpec fan(EdgeArgs a) {
        // 냉각 조건을 '팬'으로 지정했으면 회전수를 따로 말하지 않아도 팬이 도는 것으로 본다.
        //
        // 이렇게 하지 않으면 같은 팬인데 표현에 따라 비용이 사라진다(실측 회귀):
        // "팬 냉각"이라고만 하면 프리셋 열저항만 가져와 냉각 효과는 그대로인데 전력이
        // 집계되지 않고, "팬 5000rpm"이라고 해야 900J이 붙었다. 팬 전력을 모델링한 이유가
        // "공짜가 아니어야 적정 수준이라는 결론이 나온다"는 것인데, 말하는 방식에 따라
        // 그 비용이 없어지면 결론도 재현성도 함께 무너진다.
        boolean activeCooling = CoolingPreset.ACTIVE == CoolingPreset.parse(a.str("cooling", null));
        if (!a.has("fanRpm") && !activeCooling) return null;

        double ratedRpm = a.dbl("fanRatedRpm", 5000.0, 100.0, 30000.0);
        // 회전수를 명시하면 그 값이 이긴다 — "팬 냉각 + 2500rpm"은 절반 속도가 맞다.
        double rpm = a.dbl("fanRpm", activeCooling ? ratedRpm : 0.0, 0.0, 30000.0);
        double ratedPower = a.dbl("fanRatedPowerW", 0.5, 0.0, 50.0);
        return new FanSpec(rpm, ratedRpm, ratedPower);
    }

    /**
     * 팬 회전수에 맞춰 전체 열저항을 다시 계산한다. 팬이 없거나 사용자가 열저항을 직접
     * 지정한 경우에는 손대지 않는다.
     *
     * <p>보간의 두 끝점은 보드의 기존 프리셋(수동·능동 냉각)이다 — 이미 검증된 두 값을
     * 지나도록 계수를 역산하므로, 팬 모델을 켠다고 기존 결과가 어긋나지 않는다.
     */
    static ThermalParams applyFan(ThermalParams p, BoardType board, FanSpec fan, EdgeArgs a) {
        if (fan == null || a.has("rJaKPerW") || a.has("thermalOverride")) return p;
        double rJa = fan.effectiveRJa(board.rJaKPerW(CoolingPreset.PASSIVE),
                board.rJaKPerW(CoolingPreset.ACTIVE), board.rJcKPerW());
        return p.withRJa(rJa);
    }

    /**
     * 2노드 모델용 방열판 열 덩어리를 읽는다. 지정하지 않았으면 null(기존 1노드).
     *
     * <p>입력은 두 갈래다 — 저울로 잰 {@code heatsinkMassG}(+재질)이 기본이고,
     * 열용량을 직접 아는 경우엔 {@code heatsinkCThJPerK}로 넣는다. 둘 다 없으면
     * 2노드로 갈 근거가 없으므로 1노드로 둔다.
     *
     * <p>{@code rInternalKPerW}(SoC→방열판)의 기본값은 보드의 {@code rJc}(다이→패키지)다.
     * TIM 층 저항이 여기에 더해져야 정확하지만, 그 값은 방열판 배치 도구가 형상에서
     * 계산하는 것이라 여기서는 하한인 rJc를 쓰고 사용자가 덮어쓸 수 있게 둔다.
     */
    static HeatsinkMass heatsinkMass(EdgeArgs a, BoardType board, ThermalParams p) {
        double massG = a.dbl("heatsinkMassG", 0.0, 0.0, 5000.0);
        double cTh = a.dbl("heatsinkCThJPerK", 0.0, 0.0, 100000.0);
        if (massG <= 0 && cTh <= 0) return null;

        double rInternal = a.dbl("rInternalKPerW", board.rJcKPerW(), 0.05, 50.0);
        if (rInternal >= p.rJaKPerW()) {
            a.reject(ErrorCode.OUT_OF_RANGE, "rInternalKPerW", String.format(
                    "SoC→방열판 열저항(%.2f)이 전체 열저항(%.2f) 이상이다 — 전체는 내부와 "
                    + "방열판→공기의 직렬 합이므로 내부가 더 작아야 한다.", rInternal, p.rJaKPerW()));
            return null;
        }
        if (cTh > 0) return new HeatsinkMass(cTh, rInternal);

        HeatsinkLayout.Material mat = a.enumVal("heatsinkMaterial", HeatsinkLayout.Material.ALUMINUM,
                HeatsinkLayout.Material::parse, "aluminum, copper", false);
        return HeatsinkMass.ofMass(massG, mat, rInternal);
    }

    /**
     * {@code aiLoadProfileId}를 읽어 부하 패턴을 찾는다. 지정하지 않았으면 null(상수 부하).
     *
     * <p>오타난 id를 조용히 무시하면 "패턴을 넣었다고 믿었는데 실제로는 상수 부하로
     * 돌아간" 결과를 비교하게 된다 — 방열판 순위 비교에서는 그 차이가 곧 결론을
     * 뒤집으므로 fail-closed로 거부한다.
     */
    static AiLoadProfile aiLoadProfile(EdgeArgs a, AiLoadProfileService service) {
        String id = a.str("aiLoadProfileId", null);
        if (id == null || id.isBlank()) return null;
        AiLoadProfile found = service.find(id);
        if (found == null) {
            a.reject(ErrorCode.INVALID_ENUM, "aiLoadProfileId",
                    "허용되지 않은 값 '" + id + "'. 허용 값: "
                    + String.join(", ", service.all().stream().map(AiLoadProfile::getId).toList()));
        }
        return found;
    }

    /**
     * @param aiLoad AI 부하 패턴(선택). 호출측 도구가 {@code aiLoadProfileId}를 읽어
     *   {@link AiLoadProfileService}로 조회한 결과를 넘긴다 — 프로파일 저장소는 스프링
     *   빈이라 이 static 헬퍼가 직접 들고 있을 수 없다
     */
    static ThermalSimulator.Spec spec(EdgeArgs a, BoardType board, ThermalParams p,
                                      double defaultLoadSec, WorkloadMode defaultMode,
                                      AiLoadProfile aiLoad) {
        // 팬을 먼저 반영한다 — 열저항이 바뀌면 시정수도 바뀌므로, 아래의 적분 간격(dt)과
        // 회복 구간 기본 열저항이 모두 팬이 반영된 값을 기준으로 정해져야 한다.
        FanSpec fanSpec = fan(a);
        p = applyFan(p, board, fanSpec, a);

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
        // 2노드 여부는 인자에서 직접 읽는다 — spec()을 쓰는 도구는 전부 자동으로 지원된다.
        HeatsinkMass heatsink = heatsinkMass(a, board, p);
        return new ThermalSimulator.Spec(p, mode, targetFps, loadSec, policy, recoverySec,
                recoveryRJa, dt, sampleSec, onThrottle, aiLoad, heatsink, fanSpec);
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
