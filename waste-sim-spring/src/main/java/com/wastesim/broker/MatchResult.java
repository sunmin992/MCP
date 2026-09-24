package com.wastesim.broker;

import java.util.List;

/**
 * 후보 하나에 대한 판정.
 *
 * <p>점수만 돌려주면 왜 그 서버인지 아무도 되물을 수 없다. 맞은 자리는 {@code reasons} 에,
 * 어긋난 자리는 {@code mismatches} 에 남긴다 — 둘 다 없으면 "도메인만 맞았다" 는 뜻이고
 * 그것도 하나의 답이다.
 *
 * @param reasons    요청의 어느 항목이 카드의 어느 칸과 맞았는가. <b>양쪽을 다 적는다</b>
 * @param mismatches 요청한 것을 이 서버가 못 하는 자리와 그 사유
 */
public record MatchResult(
        String serverId,
        String name,
        int score,
        List<String> reasons,
        List<String> mismatches) {

    public MatchResult {
        reasons = List.copyOf(reasons);
        mismatches = List.copyOf(mismatches);
    }
}
