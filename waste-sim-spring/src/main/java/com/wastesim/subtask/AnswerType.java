package com.wastesim.subtask;

/**
 * 서브태스크가 받는 답변의 자료형(SDD 2.18.2 — {@code answerType}).
 *
 * <p>이 값은 두 곳에서 쓰인다. 서버 쪽에서는 {@link JangnyangSubtaskValidator}가
 * "이 답이 이 자료형인가"를 검사하는 기준이고, 클라이언트 쪽에서는 프런트엔드가
 * <b>어떤 입력 위젯을 띄울지</b> 고르는 기준이다(SDD 2.18.10). 질문을 자유 텍스트로만
 * 내려보내면 클라이언트는 시각 입력을 띄워야 하는지 선택지를 띄워야 하는지 알 수 없다.
 *
 * <p>{@code TIME}을 {@code STRING}과 따로 두는 이유는 검증 규칙이 다르기 때문이다 —
 * 문자열 길이가 아니라 HH:MM 형식과 00:00~23:59 범위를 본다. 같은 이유로
 * {@code INTEGER}와 {@code NUMBER}도 분리한다(소수를 조용히 절삭하지 않기 위해, E-03).
 */
public enum AnswerType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    /** "HH:MM" 24시간 표기. 구조화 값은 자정 기준 분(0~1439)으로 보관한다. */
    TIME,
    /** {@code allowedRange.values} 중 하나. */
    ENUM,
    /** {@code allowedRange.values} 중 여럿(다중 선택). */
    ENUM_LIST,
    STRING_LIST,
    /** "HH:MM" 값의 목록 — 하루 여러 번 수거처럼 시각이 여러 개인 항목. */
    TIME_LIST,
    /**
     * "HH:MM~HH:MM" 한 구간 — 배출 허용 창처럼 시작과 종료가 짝인 항목.
     * 구조화 값은 {@code [시작분, 종료분]}이다.
     *
     * <p>{@code TIME_LIST}로 대신할 수 없다. 그쪽은 값을 <b>오름차순으로 정렬</b>하는데
     * (수거 시각의 순서는 의미가 있으므로 옳다), 자정을 넘는 창은 정렬하면 뒤집힌다 —
     * 20:00~06:00이 06:00~20:00이 되어 <b>낮에만 버리는</b> 정반대의 창이 된다.
     * 포항시 북구의 실제 배출 창이 바로 그 자정을 넘는 창이다.
     */
    TIME_RANGE,
    /** 키→정수 맵(건물별 인원 등). */
    INTEGER_MAP,
    /**
     * 키→수 맵(직업 구성비·쓰레기 종류 비율 등).
     *
     * <p>{@code allowedRange.sumTo}가 있으면 합계까지 본다 — 비율의 합이 1이 아닌 것은
     * 항목 하나하나로는 잡히지 않고 <b>모아 놓아야</b> 드러나는 오류이기 때문이다.
     * 합을 서버가 임의로 정규화하지 않는다(D-26).
     */
    NUMBER_MAP
}
