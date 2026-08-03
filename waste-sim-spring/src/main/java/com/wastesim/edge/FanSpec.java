package com.wastesim.edge;

/**
 * 냉각팬 — <b>열저항을 낮추는 대가로 전력을 쓰는</b> 요소.
 *
 * <h3>왜 전력까지 모델링하는가</h3>
 * 팬을 "열저항을 낮춰 주는 장치"로만 다루면 최적해가 언제나 <b>최대 RPM</b>이 된다 —
 * 비용이 0이니 안 돌릴 이유가 없기 때문이다. 그러면 이 연구가 도달해야 할 결론
 * ("최고 성능보다 적정 수준이 지속가능하다")과 모델이 정반대를 가리킨다.
 *
 * <h3>두 법칙이 만드는 비대칭</h3>
 * <ul>
 *   <li><b>전력</b>은 회전수의 <b>3승</b>에 비례한다(팬 상사법칙) — RPM 2배면 전력 8배</li>
 *   <li><b>냉각</b>은 풍속의 <b>0.8승</b>에 비례한다(강제대류 상관식) — 훨씬 완만하다</li>
 * </ul>
 * 그래서 RPM을 절반만 돌려도 냉각 이득의 대부분을 얻으면서 전력은 1/8만 쓴다.
 * <b>이 비대칭이 "적정 수준에서 멈추는 것이 낫다"의 물리적 근거</b>이고, 이 클래스가
 * 계산하는 것이 정확히 그 트레이드오프다.
 *
 * <h3>팬 전력은 SoC를 데우지 않는다</h3>
 * 팬이 쓰는 전기는 모터에서 소비되고 공기로 흩어진다 — SoC 열 노드에 들어가지 않는다.
 * 그래서 온도를 적분하는 전력({@code P_SoC})과 비용을 집계하는 전력({@code P_SoC + P_fan})을
 * 반드시 구분해야 한다. 합쳐서 적분하면 팬을 켤수록 온도가 올라가는 거꾸로 된 결과가 나온다.
 *
 * @param rpm           현재 회전수. 0이면 팬을 끈 것과 같다(전력 0, 열저항은 수동 냉각 수준)
 * @param ratedRpm      정격(최대) 회전수
 * @param ratedPowerW   정격 회전수에서의 소비전력(W)
 */
public record FanSpec(double rpm, double ratedRpm, double ratedPowerW) {

    /** 강제대류 열전달계수의 풍속 지수 — {@link HeatsinkThermalModel}과 같은 값을 쓴다. */
    public static final double CONVECTION_EXPONENT = 0.8;
    /** 팬 상사법칙 — 소비전력은 회전수의 3승. */
    public static final double POWER_EXPONENT = 3.0;

    public FanSpec {
        if (ratedRpm <= 0) throw new IllegalArgumentException("정격 회전수는 0보다 커야 한다: " + ratedRpm);
        if (ratedPowerW < 0) throw new IllegalArgumentException("정격 전력은 음수일 수 없다: " + ratedPowerW);
        if (rpm < 0) throw new IllegalArgumentException("회전수는 음수일 수 없다: " + rpm);
    }

    /** 팬을 끈 상태(전력 0). 비교의 대조군이다. */
    public static FanSpec off(double ratedRpm, double ratedPowerW) {
        return new FanSpec(0, ratedRpm, ratedPowerW);
    }

    /** 정격 회전수 대비 비율(0~1). 1을 넘으면 1로 자른다. */
    public double dutyRatio() {
        return Math.min(1.0, rpm / ratedRpm);
    }

    /** 이 회전수에서의 소비전력(W). 회전수의 3승에 비례한다. */
    public double powerW() {
        return ratedPowerW * Math.pow(dutyRatio(), POWER_EXPONENT);
    }

    /**
     * 팬을 이 회전수로 돌릴 때의 전체 열저항(K/W).
     *
     * <p>공기로 나가는 몫만 팬의 영향을 받는다 — 다이→패키지({@code rInternal})는 기류와
     * 무관하므로 그대로 두고, 나머지를 풍속의 0.8승으로 낮춘다. 계수는 프리셋 두 점
     * (팬 정지 = 수동 냉각, 정격 = 능동 냉각)을 지나도록 역산하므로, 기존에 검증된
     * 두 값과 어긋나지 않으면서 그 사이를 물리적으로 이어 준다.
     *
     * @param rJaPassive   팬을 껐을 때의 전체 열저항(수동 냉각 프리셋)
     * @param rJaActive    정격 회전수에서의 전체 열저항(능동 냉각 프리셋)
     * @param rInternal    기류와 무관한 내부 열저항(보드의 rJc)
     */
    public double effectiveRJa(double rJaPassive, double rJaActive, double rInternal) {
        double airPassive = rJaPassive - rInternal;
        double airActive = rJaActive - rInternal;
        if (airPassive <= 0 || airActive <= 0) {
            throw new IllegalArgumentException(String.format(
                    "내부 열저항(%.2f)이 전체(수동 %.2f / 능동 %.2f) 이상이다 — 공기로 나갈 몫이 없다.",
                    rInternal, rJaPassive, rJaActive));
        }
        if (airActive >= airPassive) return rJaPassive;   // 팬이 이득이 없는 설정 — 그대로 둔다

        double duty = dutyRatio();
        if (duty <= 0) return rJaPassive;
        // airPassive / (1 + c·duty^0.8) 가 duty=1에서 airActive가 되도록 c를 정한다.
        double c = airPassive / airActive - 1.0;
        double air = airPassive / (1.0 + c * Math.pow(duty, CONVECTION_EXPONENT));
        return rInternal + air;
    }
}
