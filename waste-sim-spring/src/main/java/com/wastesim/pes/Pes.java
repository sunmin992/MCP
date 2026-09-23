package com.wastesim.pes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 선택이 닫힌 구조 — Pruned Entity Structure.
 *
 * <p>SES 의 열린 선택 지점(선택지·다중성·속성·조건부 결합)이 전부 하나로 정해진 상태다.
 * 실행 설정이 아니다 — 평탄화를 거쳐야 {@code SimulationConfig}가 된다.
 *
 * @param values  답변키 → 확정값
 * @param origins 답변키 → 출처. {@code USER} / {@code MODEL_DEFAULT} / {@code DERIVED}.
 *                사용자가 승인하지 않은 기본값이 섞여 있으면 임의 가정으로 집계한다
 */
public record Pes(
        String sesId,
        String sesVersion,
        Map<String, Object> values,
        Map<String, String> origins) {

    public Pes {
        // 빈 식별자를 받지 않는다. 템플릿 JSON 에 sesId/sesVersion 이 없으면
        // 카탈로그가 빈 문자열을 돌려주던 자리가 있었고(인계 문서 §4(b)), PES 는 그 값을
        // 식별자로 싣는 첫 자리다. 여기서 막지 않으면 "어느 SES 로 만든 설정인가"를
        // 되물을 수 없는 PES 가 확인 화면과 실행까지 그대로 간다.
        requireText(sesId, "sesId");
        requireText(sesVersion, "sesVersion");

        values = Map.copyOf(values);
        origins = Map.copyOf(origins);

        // 출처 없는 값은 사용자 승인 여부를 판정할 수 없다 — unapprovedDefaults() 가
        // 그것을 조용히 "승인된 값"으로 세어 버린다.
        for (String key : values.keySet()) {
            if (!origins.containsKey(key)) {
                throw new IllegalArgumentException(
                        "값의 출처가 없습니다: " + key + " — 승인 여부를 판정할 수 없습니다");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " 가 비어 있습니다 — 어느 SES 에서 나온 PES 인지 밝힐 수 없습니다");
        }
    }

    /** 사용자가 승인하지 않은 기본값 목록. 비어 있지 않으면 확인 단계에서 노출한다. */
    public Map<String, Object> unapprovedDefaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if ("MODEL_DEFAULT".equals(origins.get(k))) out.put(k, v);
        });
        return out;
    }
}
