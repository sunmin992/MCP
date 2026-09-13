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

    public ValueSource {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("출처 종류가 없습니다.");
        }
    }
}
