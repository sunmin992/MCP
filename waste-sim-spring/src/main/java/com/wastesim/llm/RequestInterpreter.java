package com.wastesim.llm;

import java.util.List;

/**
 * 자유 문장에서 설계도 값을 뽑는다. <b>판정하지 않는다.</b>
 *
 * <p>인터페이스로 둔 이유는 테스트에서 LLM을 부르지 않기 위한 것이다. 고정 응답 스텁으로
 * 흐름을 검증하고 실제 호출은 별도 통합 확인으로 분리한다 — TMAP·OSRM에 쓴 방식과 같다.
 */
@FunctionalInterface
public interface RequestInterpreter {

    /**
     * @param answerFields 뽑을 수 있는 필드 이름 목록. 이 밖의 필드를 내면 호출부가 버린다
     * @throws InterpreterException 서비스 장애·형식 오류. 호출부는 문항 흐름으로 넘긴다
     */
    RequestExtraction extract(String request, List<String> answerFields)
            throws InterpreterException;
}
