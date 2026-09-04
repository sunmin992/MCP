package com.wastesim.llm;

import java.util.List;

/**
 * 이 요청으로 시뮬레이터를 만들 수 있는가, 없으면 무엇이 필요한가.
 *
 * @param whatWouldBeNeeded 부족한 것. <b>거부라면 비어 있을 수 없다</b> — "안 됩니다"로
 *                          끝나는 거부는 사용자가 다음에 무엇을 할지 모른다
 */
public record FeasibilityVerdict(boolean feasible, Reason reason, String message,
                                 List<Missing> whatWouldBeNeeded) {

    public FeasibilityVerdict {
        whatWouldBeNeeded = whatWouldBeNeeded == null ? List.of()
                                                      : List.copyOf(whatWouldBeNeeded);
    }

    public enum Reason {
        /** 이 시스템은 장량동만 다룬다. 구역 정의와 주민 모델이 지역에 묶여 있다 */
        OUT_OF_REGION,
        /** 요청이 가리키는 변수가 DEVS 모델에 없다(가격·분리율 등) */
        AXIS_NOT_IN_MODEL,
        /** 결론에 필요한 데이터가 없다(지점 좌표 0곳) */
        DATA_UNAVAILABLE,
        /** 실행할 시뮬레이션이 아니라 사실 조회다 */
        NOT_A_SIMULATION
    }

    /**
     * @param obtainable API로 자동 수집할 수 있는가. 사람이 채워야 하는 것을 숨기면
     *                   자동으로 될 것처럼 읽힌다
     */
    public record Missing(String item, boolean obtainable, String note) {}

    public static FeasibilityVerdict ok() {
        return new FeasibilityVerdict(true, null, null, List.of());
    }
}
