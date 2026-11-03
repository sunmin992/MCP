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
 * @param ambiguities 카드가 그 칸을 적지 않아 할 수 있는지 판단할 수 없는 자리
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
        List<String> ambiguities,
        List<RequestAdjustment> requestAdjustments,
        List<com.wastesim.contract.RequirementContract.Check> conditionChecks) {

    public MatchResult(String serverId, String name, String endpoint, boolean fictional, List<String> reasons,
            List<String> mismatches, List<String> ambiguities, List<RequestAdjustment> requestAdjustments) {
        this(serverId, name, endpoint, fictional, reasons, mismatches, ambiguities, requestAdjustments, List.of());
    }

    public MatchResult {
        conditionChecks = List.copyOf(conditionChecks);
        reasons = List.copyOf(reasons);
        mismatches = List.copyOf(mismatches);
        ambiguities = List.copyOf(ambiguities);
        requestAdjustments = List.copyOf(requestAdjustments);
    }

    /** 요청을 고치지 않고 그대로 보낼 수 있는가. 확실하지 않으면 일치로 보지 않는다. */
    public boolean fitsAsIs() {
        return mismatches.isEmpty() && ambiguities.isEmpty();
    }

    /** 못 한다는 자리는 없고 애매한 자리만 남았는가 — 되물으면 풀릴 수 있다. */
    public boolean blockedOnlyByAmbiguity() {
        return mismatches.isEmpty() && !ambiguities.isEmpty();
    }
}
