package com.wastesim.edge.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 듀얼 팬 배치의 <b>경험적</b> 냉각 점수 모델.
 *
 * <p>출처는 dual_fan_all_layouts_preliminary.xlsx와 그 생성 스크립트
 * build_fan_layouts.mjs(2026-08-27)다. CFD도 실측도 아니고, 팬 풍량·정압, 함체 치수,
 * 통풍구 개구율, 방열판 사양은 하나도 반영돼 있지 않다. 용도는 <b>실측할 배치 후보를
 * 줄이는 것</b> 하나뿐이다.
 *
 * <h3>기존 열 스택을 참조하지 않는다</h3>
 * {@code FanArraySpec}은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고 못 박고
 * 있는데, 이 클래스는 정확히 그 차이를 만드는 모델이다. 그래서 열 스택
 * ({@code ThermalSimulator}·{@code HeatsinkThermalModel}·{@code ThermalParams}·
 * {@code ThermalRun})을 <b>import 하지 않는다</b> — 의존성이 없으면 임시 계수가 물리
 * 모델 결과에 새어 들어갈 경로 자체가 없다(설계 D-43, FanLayoutIsolationTest가 고정).
 */
public final class FanLayoutScoreModel {

    private FanLayoutScoreModel() {}

    /** 방향 조합 4가지 — 엑셀의 열거 순서를 그대로 따른다(ID가 시트와 어긋나면 안 된다). */
    private static final FanFlowRole[][] FLOW_PAIRS = {
            {FanFlowRole.INTAKE,  FanFlowRole.INTAKE},
            {FanFlowRole.INTAKE,  FanFlowRole.EXHAUST},
            {FanFlowRole.EXHAUST, FanFlowRole.INTAKE},
            {FanFlowRole.EXHAUST, FanFlowRole.EXHAUST}
    };

    /**
     * 주어진 위치들에서 만들 수 있는 모든 배치를 센다.
     *
     * <p>순서가 고정돼 있어야 ID(P01~P60)가 엑셀 시트와 일치한다 — 바깥 루프가 위치 i,
     * 안쪽 루프가 j &gt; i, 그 안에서 방향 4가지다. 이 순서를 바꾸면 골든 회귀 테스트가
     * 깨진다.
     *
     * @param positions 열거에 포함할 위치(2곳 이상). 호출측이 중복·개수를 미리 검증한다
     */
    public static List<FanLayoutCandidate> enumerateAll(List<FanMountPosition> positions) {
        List<FanLayoutCandidate> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                for (FanFlowRole[] flows : FLOW_PAIRS) {
                    String id = String.format("P%02d", out.size() + 1);
                    out.add(new FanLayoutCandidate(
                            id, positions.get(i), flows[0], positions.get(j), flows[1]));
                }
            }
        }
        return out;
    }
}
