package com.wastesim.broker;

/** 요청 항목 하나를 카드와 대조한 결과 중 그대로 받을 수 없는 쪽 — 일치는 조정이 없다. */
public enum Classification {
    /** 카드가 못 한다고 하거나 목록에 없다. */
    MISMATCH,
    /** 카드가 그 칸을 적지 않아 할 수 있는지 판단할 수 없다. */
    AMBIGUOUS
}
