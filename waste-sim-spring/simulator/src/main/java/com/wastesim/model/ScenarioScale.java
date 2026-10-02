package com.wastesim.model;

/**
 * 시나리오의 <b>규모</b> — 건물 수·동당 거주민·배출 지점 용량·차량을 한 묶음으로 정한다.
 *
 * <p>{@link ScenarioPreset}과 다른 축이다. 프리셋은 <b>누가 사는지</b>(직업 구성)를 정하고,
 * 이쪽은 <b>얼마나 큰지</b>를 정한다. 둘은 함께 쓸 수 있다.
 *
 * <h2>왜 규모를 이름 붙여 두는가</h2>
 *
 * <p>논문 기준선(4동 25명)에서는 <b>용량 축이 작동하지 않는다.</b> 하루 생성량이
 * 4 × 25 × 0.9 = 90kg인데 5톤 트럭 용량이 5000kg이라, 트럭 가동률이 어떤 설정에서도
 * 1.8%로 고정되고 미수거는 항상 0이다. 정차시간을 0·5·10분으로 흔들어도 순회 시간만
 * 움직이고 나머지 세 지표는 꿈쩍하지 않는다 — 즉 그 시나리오로는 용량·가동률·처리 지연을
 * 논할 수 없다.
 *
 * <p>측정해 보니 <b>건물 수를 늘리는 것으로는 해결되지 않는다.</b> 노드 id가
 * {@code Node_A~Z}라 26동이 상한이고, 26동 25명이어도 585kg/일로 5톤의 11.6%다.
 * 값싼 해법은 <b>차량을 실제 규모로 줄이는 것</b>이었다 — 같은 585kg/일에 1톤 차량이면
 * 57.8%가 된다. 장량동 이면도로 수거에는 1톤이 오히려 현실적이다(골목 진입 가능,
 * 기동성 1.6).
 *
 * <p>한 가지 예상은 틀렸다. 배출 지점 용량(30kg)이 트럭 적재를 제한할 것으로 봤지만
 * 제한하지 않는다 — 지점은 용량을 넘겨 쌓이고(넘침 민원만 발생) 트럭은 그것을 다 실어
 * 간다. 그래서 가동률은 <b>생성량/일 ÷ 트럭 용량</b>일 뿐이고, 지점 용량은 민원 쪽에만
 * 작용한다.
 *
 * <h2>이 값들은 시나리오 설정이고 실측 근거가 아니다</h2>
 *
 * <p>동당 40명은 장량동 실제 세대수를 조사해서 얻은 값이 아니라, 용량 축이 작동하는
 * 구간을 찾아 고른 값이다. 실제 근거를 대려면 포항시 북구 1일 생활폐기물 발생량과 장량동
 * 인구를 확인해야 한다.
 */
public enum ScenarioScale {

    /**
     * 논문 기준선 — 4동 25명, 지점 30kg, 5톤 1대.
     *
     * <p>{@link SimulationConfig}의 필드 기본값과 같다. 논문 재현이 이 값에 걸려 있어서
     * 필드 기본값은 바꾸지 않는다. 용량 축은 이 규모에서 작동하지 않는다(가동률 1.8% 고정).
     */
    PAPER_BASELINE("논문 기준선", 4, 25, 30.0, "LARGE_5TON", 1,
            "논문 재현용. 하루 생성 90kg 대 5톤 용량이라 트럭 가동률이 1.8%로 고정되고 "
                    + "미수거가 항상 0이다 — 용량·가동률·처리 지연을 이 규모로 논할 수 없다."),

    /**
     * 용량 축이 작동하는 장량동 규모 — 26동 40명, 지점 60kg, 1톤 1대.
     *
     * <p>하루 생성 936kg 대 1톤 용량으로 <b>가동률 92.5%</b>, 미수거 0, 민원 0이다.
     * 여기서 각 축이 살아 있다 —
     *
     * <ul>
     *   <li><b>미수거</b>는 동당 42명(983kg/일, 97.1%)에서 처음 0.4kg으로 나타나고 43명에서
     *       2,930kg, 50명에서 64,406kg으로 뛴다. 기울기가 아니라 <b>절벽</b>이므로 민감도를
     *       볼 구간은 가동률 97~99% 사이다.</li>
     *   <li><b>트럭 대수</b>는 1→2대에서 92.5%→46.3%로 정확히 반씩 나뉜다.</li>
     *   <li><b>지점 용량</b>은 40명/동에 30kg이면 넘침 민원 9,667건, 60kg이면 0건이다.
     *       세대수에 맞춰 키우지 않으면 결과가 넘침 하나에 지배되어 다른 요인이 보이지 않는다.
     *       그래서 60kg을 골랐다.</li>
     * </ul>
     */
    JANGRYANG_CAPACITY("장량동 용량 규모", 26, 40, 60.0, "SMALL_1TON", 1,
            "용량 축이 작동하는 규모. 하루 생성 936kg 대 1톤 용량으로 가동률 92.5%, "
                    + "미수거 0, 민원 0에서 시작한다. 동당 40명은 실측 세대수가 아니라 "
                    + "용량 축이 작동하는 구간을 찾아 고른 값이다.");

    public final String labelKo;
    public final int numBuildings;
    public final int residentsPerBuilding;
    public final double capacityKg;
    public final String truckType;
    public final int numTrucks;
    public final String note;

    ScenarioScale(String labelKo, int numBuildings, int residentsPerBuilding, double capacityKg,
                  String truckType, int numTrucks, String note) {
        this.labelKo = labelKo;
        this.numBuildings = numBuildings;
        this.residentsPerBuilding = residentsPerBuilding;
        this.capacityKg = capacityKg;
        this.truckType = truckType;
        this.numTrucks = numTrucks;
        this.note = note;
    }

    /** 이 규모의 하루 생성량(kg) — {@code wasteMeanKg}를 곱해서 얻는다. */
    public double dailyGenerationKg(double wasteMeanKg) {
        return numBuildings * residentsPerBuilding * wasteMeanKg;
    }

    /**
     * 이 규모로 트럭 용량을 얼마나 쓰게 되는가(비율). 가동률이 이 값 근처로 나온다 —
     * 배출 지점 용량은 적재를 제한하지 않으므로 이 비율이 사실상 가동률의 상한이다.
     */
    public double capacityPressure(double wasteMeanKg) {
        return dailyGenerationKg(wasteMeanKg) / (TruckType.fromName(truckType).capacityKg * numTrucks);
    }

    /**
     * 이 규모를 설정에 얹는다. <b>이 다섯 필드만</b> 건드리고 나머지는 그대로 둔다 —
     * 수거 시각·직업 구성·교통 설정 등은 규모와 무관한 축이다.
     */
    public SimulationConfig applyTo(SimulationConfig c) {
        c.setNumBuildings(numBuildings);
        c.setResidentsPerBuilding(residentsPerBuilding);
        c.setCapacity(capacityKg);
        c.setTruckType(truckType);
        c.setNumTrucks(numTrucks);
        return c;
    }

    /** 이 규모로 시작하는 새 설정. */
    public SimulationConfig newConfig() {
        return applyTo(new SimulationConfig());
    }

    /** 이름 → 규모. 대소문자·하이픈 무관. 알 수 없으면 예외(검증기가 잡는다). */
    public static ScenarioScale fromName(String name) {
        if (name == null || name.isBlank()) return PAPER_BASELINE;
        String key = name.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        for (ScenarioScale s : values()) {
            if (s.name().equals(key) || s.labelKo.equals(name.trim())) return s;
        }
        throw new IllegalArgumentException("알 수 없는 시나리오 규모: " + name
                + " (허용: PAPER_BASELINE, JANGRYANG_CAPACITY)");
    }
}
