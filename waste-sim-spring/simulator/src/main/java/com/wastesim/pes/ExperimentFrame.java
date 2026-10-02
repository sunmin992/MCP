package com.wastesim.pes;

import java.util.List;

/**
 * PES 에서 <b>마지막으로 열려 있는 자리</b>를 닫는 실험 조건.
 *
 * <p>모델 결정과 실험 결정을 나눠 두는 이유가 여기 있다 — 실험 변수는 모델 구조상
 * 값이 하나로 정해지지 않은 채 남고, 프레임이 그것을 N개로 펼쳐 완전히 닫힌 설정을
 * N벌 만든다.
 *
 * @param variableAnswerKey 실험 변수의 답변키. 템플릿에 있는 키여야 한다
 * @param values            비교할 값들
 * @param observations      관측할 결과 필드 이름
 */
public record ExperimentFrame(
        String variableAnswerKey,
        List<Object> values,
        List<String> observations) {

    public ExperimentFrame {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("실험 변수의 비교 값이 비어 있습니다.");
        }
        values = List.copyOf(values);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
