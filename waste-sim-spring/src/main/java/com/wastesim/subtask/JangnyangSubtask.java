package com.wastesim.subtask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 고정 서브태스크 하나 — 사용자에게 나가는 질문 한 개와, 그 답을 받아 검증하고
 * 완료로 판정하는 데 필요한 모든 규칙(SRS 1.14.2 FR-120, SDD 2.18.2).
 *
 * <p><b>왜 열 개를 전부 들고 있는가</b>: 질문만 있고 검증 규칙이 없으면 서버는 답을
 * 받아도 그것이 맞는 답인지 판정할 수 없고, 재질문 문장이 없으면 서버가 상황에 맞춰
 * 문장을 새로 지어야 한다(FR-127이 금지한다). 이 열 항목이 한 record에 함께 있어야
 * "질문은 서버가 소유한다"(D-44)가 실제로 성립한다 — 일부만 들고 있으면 나머지는
 * 결국 호출부나 LLM이 채우게 된다.
 *
 * <p>이 record는 <b>불변</b>이다(FR-122·UT-302). 카탈로그가 돌려준 서브태스크를 통해
 * 질문·필수 여부를 바꿀 수 있으면, "LLM이 질문을 수정할 수 없다"는 보장이 프롬프트
 * 수준의 약속으로 내려앉는다 — 이 프로젝트는 그런 보장을 경로 자체를 없애서 얻는다.
 *
 * @param id                  세트 안에서 유일한 서브태스크 식별자(예: "ST-01")
 * @param order               수행 순서(1부터, 세트 안에서 중복·누락 없음)
 * @param question            사용자에게 그대로 나가는 질문 문장 — 서버 자산이다
 * @param answerField         이 답이 채우는 <b>단 하나의</b> 필드명(FR-125)
 * @param answerType          입력 자료형 — 검증 기준이자 프런트엔드 위젯 선택 기준
 * @param required            세트 수준 필수 여부. 시나리오별 추가 필수는
 *                            {@link JangnyangCompletenessChecker}가 따로 본다(FR-130)
 * @param allowedRange        허용 범위
 * @param validationRule      검증 규칙을 사람이 읽는 문장으로 적은 것(감사·문서화용)
 * @param retryQuestion       재질문 문장 — 몇 번을 틀려도 이 문장 그대로 나간다(FR-127, D-47)
 * @param completionCondition 이 서브태스크가 완료로 판정되는 조건
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JangnyangSubtask(
        String id,
        int order,
        /**
         * 이 질문이 속한 사용자 화면 단계(1~8). 사용자에게는 ST 번호가 아니라 이 단계
         * 이름이 보인다 — ST-021 같은 식별자는 서버가 답변을 추적하는 데만 쓴다.
         */
        int group,
        /**
         * 수집 단계인가, 확인 단계인가.
         *
         * <p>{@code CONFIRM}(ST-048~050)은 질문으로 묻지 않는다. 조립된 시나리오가 있어야
         * 보여줄 수 있는 항목이라, 미리보기 화면이 그 자리를 대신하고 승인 시 한꺼번에
         * 기록된다. 세트에서 빼지 않는 이유는 "50개를 생략 없이 유지한다"는 규약 때문이다.
         */
        SubtaskStage stage,
        String question,
        String answerField,
        AnswerType answerType,
        boolean required,
        /**
         * "해당 없음"·"기본값 사용" 같은 답을 정식 답변으로 받아들이는가.
         *
         * <p>고정 세트는 관련 없는 항목도 생략하지 않고 묻는다. 그래서 "이번 실험과
         * 무관하다"는 것도 <b>답변의 한 종류</b>여야 한다 — 그러지 않으면 사용자는 답할 수
         * 없는 질문 앞에서 막힌다. 반대로 목적·수거 시각처럼 없으면 실험이 성립하지 않는
         * 항목은 이 값이 false다.
         */
        boolean allowsNotApplicable,
        AllowedRange allowedRange,
        String validationRule,
        String retryQuestion,
        String completionCondition) {

    /**
     * FR-120이 요구하는 항목이 하나도 비지 않았는가 — 카탈로그가 리소스를 읽은 직후
     * 확인하고, UT-298이 같은 기준으로 다시 확인한다.
     *
     * <p>기동 시점에 검사하는 이유: 질문 하나가 검증 규칙 없이 세트에 들어가면 그
     * 항목만 "무엇을 답해도 통과"가 되는데, 이는 실행 시점에 조용히 드러난다.
     * 리소스를 읽을 때 터뜨려서 잘못된 세트로 서버가 뜨지 않게 한다.
     */
    public boolean isFullySpecified() {
        return notBlank(id)
                && order > 0
                && group > 0
                && stage != null
                && notBlank(question)
                && notBlank(answerField)
                && answerType != null
                && allowedRange != null && allowedRange.isDeclared()
                && notBlank(validationRule)
                && notBlank(retryQuestion)
                && notBlank(completionCondition);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
