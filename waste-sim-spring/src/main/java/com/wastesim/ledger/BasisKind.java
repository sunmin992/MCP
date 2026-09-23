package com.wastesim.ledger;

/**
 * 값의 근거가 어디서 왔는가.
 *
 * <p>규정·측정과 모델 기본값을 가르는 것이 핵심이다 — 기본값으로 실행한 결과를
 * 규정에 따른 결과처럼 읽으면 안 된다.
 */
public enum BasisKind {

    /** 법령·조례·공식 고시. 예: 포항시 북구 배출 시각 20:00~06:00. */
    REGULATION {
        @Override
        public boolean canFillWithoutAsking() { return true; }
        @Override
        public boolean needsModelDefaultNotice() { return false; }
    },

    /** 실측. 예: TMAP 288회 조회로 만든 지체 배수. */
    MEASURED {
        @Override
        public boolean canFillWithoutAsking() { return true; }
        @Override
        public boolean needsModelDefaultNotice() { return false; }
    },

    /** 모델 기본값. 실측이 아니며 논문값이거나 구현상의 초기값이다. */
    MODEL_DEFAULT {
        @Override
        public boolean canFillWithoutAsking() { return true; }
        @Override
        public boolean needsModelDefaultNotice() { return true; }
    },

    /** 사용자가 직접 준 값. */
    USER {
        @Override
        public boolean canFillWithoutAsking() { return false; }
        @Override
        public boolean needsModelDefaultNotice() { return false; }
    },

    /** 실험 의도. */
    EXPERIMENT_INTENT {
        @Override
        public boolean canFillWithoutAsking() { return false; }
        @Override
        public boolean needsModelDefaultNotice() { return false; }
    },

    /** 정보 부족으로 근거를 모르는 경우. */
    NONE {
        @Override
        public boolean canFillWithoutAsking() { return false; }
        @Override
        public boolean needsModelDefaultNotice() { return false; }
    };

    /**
     * 이 근거로 묻지 않고 값을 채울 수 있는가.
     *
     * @return 규정, 측정, 모델 기본값은 충분하지만, 사용자 입력, 실험 의도, 근거 없음은
     *         부족하다
     */
    public abstract boolean canFillWithoutAsking();

    /**
     * 이 근거로 채운 값이 모델 기본값임을 표시해야 하는가.
     *
     * <p>바깥에서 대조할 곳이 없는 근거는 결과에 표시를 붙여야 사용자가 "이 값이
     * 정말 규정을 따르는가?"를 검증할 수 있다.
     *
     * @return MODEL_DEFAULT만 true
     */
    public abstract boolean needsModelDefaultNotice();
}
