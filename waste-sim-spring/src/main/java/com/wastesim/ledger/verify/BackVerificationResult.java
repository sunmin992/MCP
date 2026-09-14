package com.wastesim.ledger.verify;

import java.util.List;

/**
 * 역검증 결과. 막은 사유를 <b>전부</b> 모은다.
 *
 * <p>첫 불일치에서 멈추면 고치고 다시 돌리기를 반복해야 하고, 그 반복 중에 나중 불일치가
 * 앞의 것 때문에 생긴 것인지 따로 있는 것인지 구분되지 않는다.
 */
public record BackVerificationResult(List<String> blocks) {

    public BackVerificationResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public boolean passed() {
        return blocks.isEmpty();
    }
}
