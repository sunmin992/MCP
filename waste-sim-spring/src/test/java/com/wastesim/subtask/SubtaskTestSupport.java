package com.wastesim.subtask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 세션 테스트가 매번 조립하던 것을 한자리로 모은다. 서비스 조립과 "필드명으로 서브태스크
 * ID 찾기"는 세트 버전이 바뀌면 같이 바뀌는데, 테스트마다 복사해 두면 한 곳만 고치고
 * 나머지가 조용히 낡는다.
 *
 * <p>서비스 조립 자체는 {@link TestSubtaskFixtures}에 이미 있는 실제 조립을 그대로 물린다 —
 * 여기서 새로 만들면 "검증기·조립기는 언제나 진짜를 쓴다"는 의도가 두 곳으로 갈라진다.
 */
final class SubtaskTestSupport {

    private SubtaskTestSupport() { }

    static SubtaskSessionService service() {
        return TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
    }

    /** 답변 필드명으로 이 세션의 서브태스크 ID를 찾는다. 없으면 {@code null}. */
    static String idOfField(SubtaskSessionService sessions, String sessionKey, String field) {
        JangnyangSubtaskSession session = sessions.activeSession(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(session);
        for (JangnyangSubtask s : def.ordered()) {
            if (field.equals(s.answerField())) return s.id();
        }
        return null;
    }

    /**
     * 이 세션의 모든 수집 질문에 유효한 답을 넣어 READY까지 보낸다.
     *
     * <p>질문을 하나씩 나열하지 않는 이유는 세트 버전이 바뀌면 그 목록이 통째로 낡기
     * 때문이다. 세션이 "다음 질문"이라고 알려 주는 것을 따라간다.
     *
     * <p>{@code step.subtask()}가 아니라 {@code step.question()}을 쓴다 —
     * {@link SubtaskSessionService.Step}의 실제 컴포넌트 이름이 그것이다.
     */
    static void answerEverything(SubtaskSessionService sessions, String sessionKey) {
        sessions.start(sessionKey);
        for (int guard = 0; guard < 200; guard++) {
            SubtaskSessionService.Step step = sessions.currentStep(sessionKey);
            JangnyangSubtask next = step.question();
            if (next == null) return;
            sessions.submit(sessionKey, next.id(), sampleAnswerFor(next), null);
        }
        throw new IllegalStateException("200번 답해도 질문이 끝나지 않았다 — 재질문 고리를 의심하라");
    }

    /**
     * 이 서브태스크가 받아들일 만한 값 하나. 허용 범위·선택지에서 고른다.
     *
     * <p>{@code AnswerType}이 다루는 자료형은 ENUM 하나가 아니다 — TIME·TIME_LIST·
     * TIME_RANGE·STRING_LIST·맵형까지 있고, {@link JangnyangSubtaskValidator}는 그
     * 자료형에 맞는 모양이 아니면 값을 거부한다. 거부된 답은 같은 질문으로 되돌아오므로
     * (FR-127), 자료형 하나라도 빠뜨리면 {@link #answerEverything}이 200번 안에 끝나지
     * 못하고 재질문 고리에 빠진다.
     */
    static Object sampleAnswerFor(JangnyangSubtask s) {
        AllowedRange r = s.allowedRange();
        // 선택지가 선언된 자료형(ENUM·ENUM_LIST·값이 있는 STRING_LIST)은 첫 선택지가
        // 언제나 유효한 답이다 — 목록형이어도 검증기가 콤마 구분 문자열 하나를 목록으로
        // 받아들이므로 그대로 돌려줘도 된다.
        if (r != null && r.values() != null && !r.values().isEmpty()) return r.values().get(0);
        return switch (s.answerType()) {
            case INTEGER -> r != null && r.min() != null ? r.min().intValue() : 1;
            case NUMBER -> r != null && r.min() != null ? r.min() : 1.0;
            case BOOLEAN -> Boolean.TRUE;
            case STRING -> sampleString(r);
            case TIME -> "09:00";
            case TIME_LIST -> List.of("09:00");
            case TIME_RANGE -> "09:00~10:00";
            // routeSequence는 허용값이 없는 STRING_LIST다 — 검증기가 그 필드에만 따로
            // Node_A~Node_Z 형식을 요구한다(JangnyangSubtaskValidator.NODE_ID). 그 밖의
            // 값 없는 목록형은 형식 제약이 없어 아무 문자열이나 받는다.
            case STRING_LIST, ENUM_LIST -> "routeSequence".equals(s.answerField())
                    ? List.of("Node_A") : List.of("샘플");
            case INTEGER_MAP -> sampleMap(r, true);
            case NUMBER_MAP -> sampleMap(r, false);
            case ENUM -> throw new IllegalStateException(
                    "ENUM인데 허용값이 비어 있다(카탈로그 기동 검사를 통과했다면 있을 수 없다): " + s.id());
        };
    }

    /** minLength·maxLength를 지키는 표본 문자열. */
    private static String sampleString(AllowedRange r) {
        int min = r != null && r.minLength() != null ? r.minLength() : 2;
        int max = r != null && r.maxLength() != null ? r.maxLength() : Integer.MAX_VALUE;
        StringBuilder sb = new StringBuilder("테스트 목적 문장");
        while (sb.length() < min) sb.append("x");
        if (sb.length() > max) sb.setLength(max);
        return sb.toString();
    }

    /**
     * 맵형 표본 하나. 허용 키가 선언돼 있으면 그 첫 키를, 아니면(정수맵의 노드 ID 규칙)
     * {@code Node_A}를 쓴다. {@code sumTo}가 있으면 항목이 하나뿐이므로 그 값 자체가
     * 합계가 되게 맞춘다.
     */
    private static Map<String, Object> sampleMap(AllowedRange r, boolean integral) {
        String key = (r != null && !r.valuesOrEmpty().isEmpty()) ? r.valuesOrEmpty().get(0) : "Node_A";
        double value = r != null && r.min() != null ? r.min() : 1.0;
        if (r != null && r.sumTo() != null) value = r.sumTo();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, integral ? (Object) (int) value : (Object) value);
        return out;
    }
}
