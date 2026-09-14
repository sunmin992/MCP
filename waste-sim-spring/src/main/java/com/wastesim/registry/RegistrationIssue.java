package com.wastesim.registry;

/**
 * 등록 검증에서 걸린 것 하나.
 *
 * <p><b>왜 거부와 보고를 가르는가</b>: 계약이 덮지 못한 입력 필드는 오류가 아니다 —
 * 계약을 늘릴지 그 필드가 이번 실험의 대상이 아닌지는 사람이 판단할 일이다. 전부 거부로
 * 만들면 판단할 자리가 사라지고, 전부 보고로 만들면 지어낸 ID가 실행 경로에 들어간다.
 */
public record RegistrationIssue(Severity severity, String rule, String detail) {

    public enum Severity {
        /** 등록할 수 없다. */
        REJECT,
        /** 등록은 되지만 사람이 봐야 한다. */
        REPORT
    }
}
