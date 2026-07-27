package com.wastesim.edge;

/**
 * 실험 대상 임베디드 보드와 그 열/전력 기본값(R&E "라즈베리파이 기반 고부하 AI 추론
 * 환경에서의 쓰로틀링 및 회복 동역학" 실험 요인 §3 "대상 보드").
 *
 * <p><b>이 값들은 문헌·데이터시트 기반 초기 추정치다.</b> 학생 실측으로 반드시
 * 캘리브레이션해야 한다({@code calibrate_edge_thermal_model} 도구 → 얻은
 * {@code thermalOverride}를 시뮬레이션 도구에 그대로 넣으면 실측 보정된 예측이
 * 나온다). 기본값을 "정답"으로 쓰지 말 것 — 시뮬레이터의 목적은 실측 없이 답을
 * 내는 게 아니라, 실측 몇 점으로 나머지 조건을 외삽하는 것이다.
 *
 * <p>열저항 분해: {@code rJa(bare) = rJc + rRest} 로 본다. {@code rJc}는 다이→
 * 패키지 표면(냉각 방식과 무관하게 항상 직렬로 남는 하한), {@code rRest}는
 * 패키지 표면→주변 공기(PCB·자연대류 경로). 방열판을 붙이면 {@code rRest}와
 * 방열판 경로가 <b>병렬</b>로 붙는다({@link HeatsinkThermalModel}).
 */
public enum BoardType {

    /** Raspberry Pi 4B (BCM2711, 4코어 Cortex-A72 1.5GHz). */
    PI4("Raspberry Pi 4B", "BCM2711",
            2.7, 3.9, 1500, 1200, 600, 12.0, 12.0,
            2.0, 9.5, 6.0, 3.2, 15.0),

    /** Raspberry Pi 5 (BCM2712, 4코어 Cortex-A76 2.4GHz). 다이·소비전력이 커서 무냉각은 사실상 상시 스로틀링. */
    PI5("Raspberry Pi 5", "BCM2712",
            3.0, 9.0, 2400, 1900, 1000, 15.0, 28.0,
            1.5, 6.5, 3.9, 2.6, 16.0);

    private final String label;
    private final String soc;
    /** 유휴 소비전력(W) — 추론 부하가 0일 때도 항상 소비되는 몫. */
    private final double idlePowerW;
    /** 최대 클럭·100% 사용률에서 추가되는 동적 소비전력(W). */
    private final double dynamicPowerW;
    private final int maxClockMhz;
    /** 소프트 온도 제한(80℃) 구간에서 펌웨어가 낮추는 클럭(MHz) — 하드 스로틀링만큼 세게 떨어뜨리지는 않는다. */
    private final int softFloorClockMhz;
    /** 하드 스로틀링(85℃) 시 펌웨어가 떨어뜨리는 클럭 하한(MHz). */
    private final int minClockMhz;
    /** 등가 열용량(J/K) — 시정수 τ = R_ja × C_th 를 만든다. */
    private final double cThJPerK;
    /** 스로틀링이 전혀 없을 때의 최대 추론 처리량(FPS). 모델(FP32/INT8)마다 다르므로 인자로 덮어쓸 수 있다. */
    private final double maxFps;
    /** 다이→패키지 표면 열저항(K/W). 어떤 냉각을 해도 남는 하한. */
    private final double rJcKPerW;
    /** 무냉각(Bare) 전체 열저항(K/W). */
    private final double rBareKPerW;
    /** 방열판만(Passive) 전체 열저항(K/W). */
    private final double rPassiveKPerW;
    /** 팬 포함(Active) 전체 열저항(K/W). */
    private final double rActiveKPerW;
    /** SoC 패키지 한 변 길이(mm) — 방열판 접촉면 겹침 계산의 기준 사각형. */
    private final double packageSideMm;

    BoardType(String label, String soc, double idlePowerW, double dynamicPowerW,
              int maxClockMhz, int softFloorClockMhz, int minClockMhz, double cThJPerK, double maxFps,
              double rJcKPerW, double rBareKPerW, double rPassiveKPerW, double rActiveKPerW,
              double packageSideMm) {
        this.label = label;
        this.soc = soc;
        this.idlePowerW = idlePowerW;
        this.dynamicPowerW = dynamicPowerW;
        this.maxClockMhz = maxClockMhz;
        this.softFloorClockMhz = softFloorClockMhz;
        this.minClockMhz = minClockMhz;
        this.cThJPerK = cThJPerK;
        this.maxFps = maxFps;
        this.rJcKPerW = rJcKPerW;
        this.rBareKPerW = rBareKPerW;
        this.rPassiveKPerW = rPassiveKPerW;
        this.rActiveKPerW = rActiveKPerW;
        this.packageSideMm = packageSideMm;
    }

    public String label() { return label; }
    public String soc() { return soc; }
    public double idlePowerW() { return idlePowerW; }
    public double dynamicPowerW() { return dynamicPowerW; }
    public double fullLoadPowerW() { return idlePowerW + dynamicPowerW; }
    public int maxClockMhz() { return maxClockMhz; }
    public int softFloorClockMhz() { return softFloorClockMhz; }
    public int minClockMhz() { return minClockMhz; }
    public double cThJPerK() { return cThJPerK; }
    public double maxFps() { return maxFps; }
    public double rJcKPerW() { return rJcKPerW; }
    public double packageSideMm() { return packageSideMm; }

    /** 패키지 표면→공기 경로(방열판 없을 때). 방열판을 붙이면 이 경로와 병렬이 된다. */
    public double rRestKPerW() { return rBareKPerW - rJcKPerW; }

    public double rJaKPerW(CoolingPreset cooling) {
        return switch (cooling) {
            case BARE -> rBareKPerW;
            case PASSIVE -> rPassiveKPerW;
            case ACTIVE -> rActiveKPerW;
        };
    }

    public static BoardType parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase().replace("-", "").replace(" ", "").replace("_", "");
        return switch (s) {
            case "pi4", "raspberrypi4", "rpi4", "pi4b", "raspberrypi4b", "4" -> PI4;
            case "pi5", "raspberrypi5", "rpi5", "5" -> PI5;
            default -> null;
        };
    }
}
