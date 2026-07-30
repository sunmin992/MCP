package com.wastesim.edge;

/**
 * 2노드 열모델의 방열판 쪽 파라미터 — 방열판을 <b>독립된 열 덩어리</b>로 다루기 위한 값.
 *
 * <h3>왜 필요한가</h3>
 * 기존 1노드 모델은 SoC와 방열판을 온도 한 점으로 뭉뚱그린다. 부하가 일정하면 그래도
 * 충분하다 — 정상상태 온도는 {@code T_주변 + P·R_ja}로 정해지고 열용량은 식에 아예
 * 나타나지 않으므로, 방열판 순위가 열저항만으로 결정되기 때문이다.
 *
 * <p>그런데 부하가 출렁이면 이야기가 달라진다. 방열판은 <b>실온에서 시작하는 큰 냉각
 * 덩어리</b>라 피크를 흡수한다 — 질량이 클수록 온도 진폭이 작아지고, 그래서 열저항이
 * 더 나쁜 방열판이 피크 온도에서는 이길 수 있다. 이 역전이 "가성비를 따지면 최고 성능
 * 방열판이 답이 아닐 수 있다"는 연구 질문의 물리적 근거인데, 1노드 모델로는 그 현상
 * 자체를 표현할 수 없다(실험 설계 §9.5가 한계로 명시한 부분).
 *
 * <h3>지배 방정식</h3>
 * <pre>
 *   C_soc · dT_soc/dt = P − (T_soc − T_hs) / R_int
 *   C_hs  · dT_hs/dt  = (T_soc − T_hs) / R_int − (T_hs − T_주변) / R_hs
 * </pre>
 * 정상상태(양변 0)를 풀면 {@code T_soc − T_주변 = P·(R_int + R_hs)}가 되어
 * <b>R_ja = R_int + R_hs</b>다 — 즉 2노드로 바꿔도 정상상태는 1노드와 완전히 같고,
 * 달라지는 것은 <b>과도응답뿐</b>이다. 이 성질이 하위호환의 근거이자 검산 기준이다.
 *
 * @param cThJPerK       방열판 열용량(J/K). 질량 × 비열
 * @param rInternalKPerW SoC→방열판 열저항(K/W). 다이→패키지(rJc)와 TIM 층의 합이며,
 *                       전체 {@code R_ja}보다 반드시 작아야 한다(나머지가 방열판→공기)
 */
public record HeatsinkMass(double cThJPerK, double rInternalKPerW) {

    /** 알루미늄 비열 J/(kg·K). */
    public static final double C_ALUMINUM = 900.0;
    /** 구리 비열 J/(kg·K). 알루미늄의 약 43%라, 같은 질량이면 열용량이 오히려 작다. */
    public static final double C_COPPER = 385.0;

    public HeatsinkMass {
        if (cThJPerK <= 0) {
            throw new IllegalArgumentException("방열판 열용량은 0보다 커야 한다: " + cThJPerK);
        }
        if (rInternalKPerW <= 0) {
            throw new IllegalArgumentException("SoC→방열판 열저항은 0보다 커야 한다: " + rInternalKPerW);
        }
    }

    /**
     * 저울로 잰 질량에서 열용량을 만든다 — 학생이 실제로 구할 수 있는 값이 질량이라
     * 이 경로가 기본이다.
     *
     * <p>구리는 알루미늄보다 밀도가 3.3배 크지만 비열은 0.43배다. 그래서 <b>같은 부피</b>면
     * 구리 쪽 열용량이 약 1.4배 크지만, <b>같은 질량</b>이면 오히려 알루미늄이 크다 —
     * 재질 비교 실험에서 이 구분을 놓치면 결과를 거꾸로 읽는다.
     *
     * @param massGram 방열판 질량(g)
     * @param material 재질
     * @param rInternalKPerW SoC→방열판 열저항(K/W)
     */
    public static HeatsinkMass ofMass(double massGram, HeatsinkLayout.Material material,
                                      double rInternalKPerW) {
        double specificHeat = material == HeatsinkLayout.Material.COPPER ? C_COPPER : C_ALUMINUM;
        return new HeatsinkMass(massGram / 1000.0 * specificHeat, rInternalKPerW);
    }

    /**
     * 방열판→공기 열저항(K/W) — 전체에서 내부 저항을 뺀 나머지(직렬 분해).
     *
     * @param totalRJaKPerW 이 시점의 전체 열저항. 회복 구간에 팬을 켜면 값이 달라지므로
     *                      매 스텝 넘겨받는다
     * @throws IllegalArgumentException 내부 저항이 전체보다 크거나 같으면 — 물리적으로
     *                      불가능하고, 그대로 두면 음수 저항으로 온도가 발산한다
     */
    public double heatsinkToAirKPerW(double totalRJaKPerW) {
        double rest = totalRJaKPerW - rInternalKPerW;
        if (rest <= 0) {
            throw new IllegalArgumentException(String.format(
                    "SoC→방열판 열저항(%.2f K/W)이 전체 열저항(%.2f K/W)보다 크거나 같다 — "
                    + "전체는 내부와 방열판→공기의 직렬 합이므로 내부가 더 작아야 한다.",
                    rInternalKPerW, totalRJaKPerW));
        }
        return rest;
    }
}
