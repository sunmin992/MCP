package com.wastesim.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 0단계에서 브로커에 등록하는 능력 카드.
 *
 * <p>JSON 쪽에는 SES 요약·적용 범위·품질 등 더 많은 칸이 있지만, 이 레코드는 <b>서버가
 * 코드로 다루는 부분만</b> 매핑한다. 나머지는 도구가 원문 JSON 그대로 내보낸다 —
 * 중간에 자바 모델을 거치면 칸이 하나 늘 때마다 코드를 고쳐야 하고, 빠뜨린 칸은
 * 조용히 사라진다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapabilityCard(
        String serverId,
        String name,
        String endpoint,
        List<String> domain,
        List<UnsupportedItem> unsupported) {

    public CapabilityCard {
        domain = domain == null ? List.of() : List.copyOf(domain);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }
}
