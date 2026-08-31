package com.wastesim.subtask;

/**
 * 답변의 구조화 값이 어디서 왔는가(SDD 2.18.2 — "정규화 출처").
 *
 * <p>세 출처를 구분해 두는 이유는 감사성(NFR-20)이다. 결과가 이상할 때 "사용자가 그렇게
 * 답한 것"과 "LLM이 그렇게 해석한 것"과 "서버가 기본값으로 채운 것"은 대응이 전혀 다르다.
 * 한 필드로 뭉뚱그리면 셋을 구분할 방법이 사라진다.
 */
public enum SubtaskAnswerSource {
    /** 사용자가 형식에 맞는 값을 그대로 입력했다(프런트엔드 위젯 입력 포함). */
    USER_DIRECT,
    /** 자연어 답변을 LLM이 지정된 필드 하나로 정규화했다(FR-125). */
    LLM_NORMALIZED,
    /** 사용자가 답하지 않아 서버가 채웠다 — 반드시 assumptions에 남는다(D-53). */
    SERVER_DEFAULT
}
