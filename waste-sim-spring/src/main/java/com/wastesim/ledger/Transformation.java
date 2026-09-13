package com.wastesim.ledger;

import java.util.List;

/**
 * 이 값을 무엇으로 어떻게 만들었는가.
 *
 * <p><b>왜 규칙 ID만 받는가</b>: 자연어 설명을 받으면 변환을 재현할 수 없고, 재현할 수 없는
 * 변환은 감사할 수 없다. 등록된 규칙만 참조하게 하면 "이 값이 어떻게 나왔는가"의 답이
 * 언제나 실행 가능한 형태로 남는다.
 *
 * @param ruleRef        등록된 변환 규칙 ID
 * @param inputEventRefs 이 변환이 읽은 결정들의 ID
 */
public record Transformation(String ruleRef, List<String> inputEventRefs) {

    public Transformation {
        if (ruleRef == null || ruleRef.isBlank()) {
            throw new IllegalArgumentException("변환 규칙 ID가 없습니다.");
        }
        inputEventRefs = inputEventRefs == null ? List.of() : List.copyOf(inputEventRefs);
    }
}
