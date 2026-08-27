package com.wastesim.edge.layout;

/**
 * 팬 2개를 어디에 어떤 역할로 다는지 — 평가 단위 하나.
 *
 * <p>두 팬은 동일 사양(40×40 mm)이므로 위치쌍은 <b>순서 없는 조합</b>이다.
 * 열거는 그래서 {@code j > i}로만 돌고, 같은 배치를 순서만 바꿔 두 번 세지 않는다.
 *
 * @param id        열거 순번 기반 ID(P01~P60). 엑셀 시트의 조합 ID와 같다
 * @param position1 첫 팬의 장착 위치
 * @param flow1     첫 팬의 역할
 * @param position2 둘째 팬의 장착 위치
 * @param flow2     둘째 팬의 역할
 */
public record FanLayoutCandidate(String id,
                                 FanMountPosition position1, FanFlowRole flow1,
                                 FanMountPosition position2, FanFlowRole flow2) {

    /** 두 팬이 같은 자리를 차지하는가 — 물리적으로 불가능한 입력을 거르는 데 쓴다. */
    public boolean hasSamePosition() { return position1 == position2; }

    /** 두 팬의 역할이 같은가(둘 다 흡기이거나 둘 다 배기). 관통류가 아니라는 뜻이다. */
    public boolean hasSameFlow() { return flow1 == flow2; }
}
