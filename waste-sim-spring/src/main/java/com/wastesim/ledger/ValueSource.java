package com.wastesim.ledger;

import java.time.Instant;

/**
 * 이 값이 어디서 왔는가.
 *
 * <p>{@code reference}는 <b>밖에서 찾아가 대조할 수 있는 곳</b>을 가리킨다 —
 * {@link com.wastesim.subtask.BasisKind}가 규정·측정과 모델 기본값을 가르는 기준과 같다.
 *
 * @param type        {@code user_explicit} · {@code asset_contract} · {@code trusted_dataset}
 *                    · {@code calculated} · {@code mcp_result} · {@code not_applicable_by_rule}
 * @param reference   대조할 곳. 서브태스크 ID, 규정 문서, 도구 호출 ID
 * @param version     그 출처의 버전. 없으면 {@code null}
 * @param acquiredAt  언제 얻었는가. 만료 판정의 기준이다
 */
public record ValueSource(String type, String reference, String version, Instant acquiredAt) {

    /**
     * 다른 출처 종류와 달리 이 값은 단순히 기록되고 끝나지 않는다 — 재계산기가 이 문자열을
     * 다시 읽어 "이 값이 규칙이 만든 자리표시자인가"를 판단하는 분기 조건으로 쓴다.
     * 오타 하나가 그 분기를 조용히 무력화할 수 있으므로 상수로 못박아 둔다.
     */
    public static final String NOT_APPLICABLE_BY_RULE = "not_applicable_by_rule";

    /**
     * 모델 기본값으로 채운 값. {@code BasisKind.MODEL_DEFAULT}가 "밖에서 찾아가 대조할 곳이
     * 없다"고 선언한 값이며, 설계 결정 1이 결과에 표시를 붙이라고 한 대상이다.
     *
     * <p><b>왜 상태가 아니라 출처 종류에 적는가</b>: {@code DEFAULTED}는 규정 기본값과 모델
     * 기본값을 모두 담는 한 칸이라 여기에 표시를 넣으면 상태가 둘로 갈라지고, 그러면 실행
     * 허용 판정({@code executable()})까지 다시 증명해야 한다. 반면 이 구분이 말하는 것은
     * <b>대조할 곳이 있는가</b>이고, 그것은 정확히 {@code type}·{@code reference}가 답하는
     * 질문이다. 표시는 그 질문의 답이지 새로운 상태가 아니다.
     */
    public static final String MODEL_DEFAULT = "model_default";

    public ValueSource {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("출처 종류가 없습니다.");
        }
    }
}
