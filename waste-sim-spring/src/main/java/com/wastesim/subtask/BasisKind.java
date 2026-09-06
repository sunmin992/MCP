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

    /** 포항시 규정·표준데이터에서 온 값. */
    REGULATION,

    /** 우리가 측정하거나 산출한 값(OSRM·TMAP). */
    MEASURED,

    /**
     * 이 시뮬레이터가 <b>기본으로 정한 값</b>이다. 밖에서 확인할 출처가 없다.
     *
     * <p>{@link #REGULATION}·{@link #MEASURED}와 갈라 두는 기준은 하나다 — <b>읽는 사람이
     * 찾아가서 대조할 곳이 있는가.</b> 포항시 표준데이터나 TMAP 측정 기록은 있고, 모델
     * 기본값은 없다.
     *
     * <p>채우기는 하되 결과에 표시를 붙인다. "아직 확인하지 않았다"가 아니라 "확인할 대상이
     * 없다"는 뜻이므로, 언젠가 사라질 표시가 아니라 그 값의 성질이다.
     */
    MODEL_DEFAULT,

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

    /** 채우되 "모델 기본값" 표시를 붙여야 하는가. */
    public boolean needsModelDefaultNotice() {
        return this == MODEL_DEFAULT;
    }
}
