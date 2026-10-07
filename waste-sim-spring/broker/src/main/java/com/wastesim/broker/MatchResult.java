package com.wastesim.broker;

import java.util.List;

/**
 * 후보 하나에 대한 판정.
 *
 * <p>점수만 돌려주면 왜 그 서버인지 아무도 되물을 수 없다. 맞은 자리는 {@code reasons} 에,
 * 어긋난 자리는 {@code mismatches} 에 남긴다 — 둘 다 없으면 "도메인만 맞았다" 는 뜻이고
 * 그것도 하나의 답이다.
 *
 * @param endpoint   그 서버의 MCP 주소 — 명세 4단계의 "연결 정보". 없으면 LLM 이 고른 서버로 갈 수 없다
 * @param reasons    요청의 어느 항목이 카드의 어느 칸과 맞았는가. <b>양쪽을 다 적는다</b>
 * @param mismatches 요청한 것을 이 서버가 못 하는 자리와 그 사유
 * @param fictional  매칭을 시험하려고 지어낸 후보인가. 참이면 부를 수 없으므로 추천하지 않는다
 * @param requestAdjustments 어긋난 자리마다, 요청을 어떻게 고치면 이 서버를 쓸 수 있는가
 */
public record MatchResult(
        String serverId,
        String name,
        String endpoint,
        boolean fictional,
        List<String> reasons,
        List<String> mismatches,
        List<RequestAdjustment> requestAdjustments) {

    public MatchResult {
        reasons = List.copyOf(reasons);
        mismatches = List.copyOf(mismatches);
        requestAdjustments = List.copyOf(requestAdjustments);
    }

    /** 요청을 고치지 않고 그대로 보낼 수 있는가. */
    public boolean fitsAsIs() {
        return mismatches.isEmpty();
    }
}
