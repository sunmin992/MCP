package com.wastesim.subtask;

/**
 * 이 필드의 기본값이 <b>무엇에 근거하는가</b>.
 *
 * <p>"근거 유무로 가른다"를 판단이 아니라 데이터로 만들기 위한 것이다. 사람이 매번
 * "이건 물어봐야 하나"를 정하면 기준이 흔들리고, 흔들린 기준은 결국 근거 없는 값을
 * 통과시킨다 — 이 프로젝트가 지어낸 좌표로 겪은 일이다.
 *
 * <p>동작은 셋뿐이다: 자동으로 채운다 / 채우되 표시한다 / 반드시 묻는다.
 */
public enum BasisKind {

    /** 논문 DEVS 모델에서 온 값. 출처(절·표)를 함께 적는다. */
    PAPER,

    /** 포항시 규정·표준데이터에서 온 값. */
    REGULATION,

    /** 우리가 측정하거나 산출한 값(OSRM·TMAP). */
    MEASURED,

    /**
     * 기본값은 있는데 <b>출처를 확인하지 않았다.</b>
     *
     * <p>채우기는 하지만 결과에 표시를 붙인다. 보기 싫은 표시지만 사실이고, 출처를 확인해
     * 승격시키는 만큼 줄어든다 — 남은 일이 결과에 드러나는 구조다.
     */
    UNVERIFIED,

    /**
     * 근거가 없다. <b>반드시 묻는다.</b>
     *
     * <p>기본값을 두면 아무 값도 주지 않은 실행이 조용히 그 가정을 쓴다. 다른 미측정 입력을
     * 모두 막는 쪽으로 처리해 왔으므로(V-T6·V-T7) 여기서도 막는다.
     */
    NONE,

    /** 값이 아니라 사용자가 정해야 할 실험 목적. 채울 수 있는 성질이 아니다. */
    EXPERIMENT_INTENT;

    /** 묻지 않고 채울 수 있는가. */
    public boolean canFillWithoutAsking() {
        return this != NONE && this != EXPERIMENT_INTENT;
    }

    /** 채우되 출처 미확인 표시를 붙여야 하는가. */
    public boolean needsUnverifiedWarning() {
        return this == UNVERIFIED;
    }
}
