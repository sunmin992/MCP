package com.wastesim.subtask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 사용자의 수집 진행(SDD 2.18.3).
 *
 * <p>세션 키는 <b>연결 단위</b>다 — {@code sessionId="default"} 고정(D-05)은 이 계층과
 * 양립하지 않는다(D-49, NFR-18). 사용자 두 명이 각자 열 개 넘는 질문에 답하는 중에 답변이
 * 섞이면 두 사람 모두의 실험이 조용히 망가지고, 그 사실은 결과를 다 받은 뒤에야 드러난다.
 *
 * <p>상태 전이는 {@link SubtaskState}가 판정하고 이 클래스가 강제한다. 전이가 거부되면
 * 예외가 아니라 {@code false}를 돌려준다 — 이 계층의 거부는 예외 상황이 아니라 <b>정상적인
 * 응답</b>이기 때문이다(사용자가 순서를 건너뛴 것뿐이고, 호출부는 그 사실을 사용자에게
 * 문장으로 알려야 한다).
 */
public class JangnyangSubtaskSession {

    private final String sessionKey;
    private final String subtaskSetId;
    private final int version;
    private final String hash;

    /** 서브태스크 ID → 통과한 답변. 삽입 순서를 유지해 감사 로그가 답한 순서를 보존한다. */
    private final Map<String, JangnyangSubtaskAnswer> answers = new LinkedHashMap<>();
    /** 직전 검증에서 남은 오류 — 재질문에 쓴다. */
    private List<SubtaskError> lastErrors = List.of();

    private SubtaskState state = SubtaskState.NOT_STARTED;
    /** 조립된 시나리오 명세. BUILT 이후에만 채워진다. */
    private JangnyangScenarioSpec spec;

    public JangnyangSubtaskSession(String sessionKey, JangnyangSubtaskDefinition def) {
        this.sessionKey = sessionKey;
        this.subtaskSetId = def.subtaskSetId();
        this.version = def.version();
        this.hash = def.hash();
    }

    public String sessionKey() { return sessionKey; }
    public String subtaskSetId() { return subtaskSetId; }
    public int version() { return version; }
    public String hash() { return hash; }
    public SubtaskState state() { return state; }
    public JangnyangScenarioSpec spec() { return spec; }

    /** 누적 답변의 <b>복사본</b> — 세션 밖에서 답변 맵을 바꿀 수 없게 한다. */
    public Map<String, JangnyangSubtaskAnswer> answers() {
        return new LinkedHashMap<>(answers);
    }

    /**
     * 선택된 시나리오 유형. 아직 안 정했으면 {@code null}.
     *
     * <p>ID가 아니라 <b>답변 필드명</b>으로 찾는다. 세트 버전이 바뀌면 같은 값이 다른 ID에
     * 붙기 때문이다(v1은 ST-02, v2는 ST-035). 필드명은 버전을 건너도 같은 것을 가리킨다.
     */
    public String scenarioType(JangnyangSubtaskDefinition def) {
        JangnyangSubtask st = def.byAnswerField("scenarioType");
        if (st == null) return null;
        JangnyangSubtaskAnswer a = answers.get(st.id());
        return a != null && a.valid() ? String.valueOf(a.value()) : null;
    }

    /**
     * 상태를 옮긴다. 건너뛴 전이는 거부한다(FR-129·D-52).
     *
     * @return 전이했으면 true, 거부됐으면 false(상태는 그대로)
     */
    public boolean transitionTo(SubtaskState next) {
        if (!state.canTransitionTo(next)) return false;
        state = next;
        return true;
    }

    /** 검증 결과를 세션에 반영한다. 통과한 답변만 누적되고, 오류는 재질문용으로 남는다. */
    public void apply(SubtaskValidationResult result) {
        answers.clear();
        answers.putAll(result.accepted());
        lastErrors = result.errors();
    }

    /** 조립 결과를 붙인다 — {@link SubtaskState#canBuild()}를 통과한 뒤에만 호출된다. */
    public void attachSpec(JangnyangScenarioSpec spec) {
        this.spec = spec;
    }

    /**
     * 취소·초기화. 답변을 지워서 다시 시작해도 이전 답이 남지 않게 한다(UT-315).
     * 상태만 CANCELLED로 두고 답변을 남기면, 같은 키로 새 세션을 열었을 때 지난 실험의
     * 값이 조용히 이어진다.
     */
    public void cancel() {
        answers.clear();
        lastErrors = List.of();
        spec = null;
        state = SubtaskState.CANCELLED;
    }

    /**
     * 지금 물어야 할 서브태스크 — 이번 실험에 필요한 것 중 아직 유효한 답이 없는 첫 항목.
     * 전부 찼으면 {@code null}.
     *
     * <p>재질문도 이 메서드가 낸다. 직전 검증에서 실패한 항목은 답변 맵에 남지 않으므로
     * (검증기가 실패한 값을 누적하지 않는다) 같은 서브태스크가 다시 "아직 답이 없는 첫
     * 항목"이 되고, 그 결과 같은 질문이 같은 문장으로 다시 나간다(FR-127).
     */
    public JangnyangSubtask nextSubtask(JangnyangSubtaskDefinition def,
                                        JangnyangCompletenessChecker checker) {
        for (JangnyangSubtask s : plan(def, checker)) {
            JangnyangSubtaskAnswer a = answers.get(s.id());
            if (a == null || !a.valid()) return s;
        }
        return null;
    }

    /** 이번 실험에서 실제로 물을 서브태스크 목록(진행률의 분모). */
    public List<JangnyangSubtask> plan(JangnyangSubtaskDefinition def,
                                       JangnyangCompletenessChecker checker) {
        return checker.relevantSubtasks(def, scenarioType(def));
    }

    /** 클라이언트로 내보낼 진행 상태(FR-128). */
    public SubtaskProgress progress(JangnyangSubtaskDefinition def,
                                    JangnyangCompletenessChecker checker) {
        List<JangnyangSubtask> plan = plan(def, checker);
        JangnyangSubtask current = nextSubtask(def, checker);
        int total = plan.size();
        int answered = 0;
        for (JangnyangSubtask s : plan) {
            JangnyangSubtaskAnswer a = answers.get(s.id());
            if (a != null && a.valid()) answered++;
        }
        int order = current == null ? total : plan.indexOf(current) + 1;

        // 사용자가 보는 것은 ST 번호가 아니라 단계다 — "3/8 · 배출량과 수거장 조건" 뒤에
        // "질문 2"가 온다. 마지막 질문까지 답했으면 마지막 단계에 머문다.
        JangnyangSubtask shown = current != null ? current
                : (plan.isEmpty() ? null : plan.get(plan.size() - 1));
        int groupOrder = shown == null ? def.groupCount() : shown.group();
        SubtaskGroup group = def.group(groupOrder);
        List<JangnyangSubtask> inGroup = def.subtasksInGroup(groupOrder).stream()
                .filter(s -> s.stage() == SubtaskStage.COLLECT).toList();
        int questionInGroup = shown == null ? inGroup.size() : inGroup.indexOf(shown) + 1;

        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JangnyangSubtaskAnswer> e : answers.entrySet()) {
            values.put(e.getKey(), e.getValue().value());
        }
        return new SubtaskProgress(subtaskSetId, version, hash, state,
                current == null ? null : current.id(), order, total,
                total == 0 ? 1.0 : (double) answered / total,
                groupOrder, def.groupCount(),
                group == null ? "" : group.name(),
                group == null ? "" : group.description(),
                Math.max(questionInGroup, 1), Math.max(inGroup.size(), 1),
                values, new ArrayList<>(lastErrors));
    }

    /**
     * 확인 단계(ST-048~050)를 한꺼번에 채운다 — 미리보기 화면이 그 셋을 대신하기 때문이다.
     *
     * <p>세트에서 빼지 않는 이유는 "50개를 생략 없이 유지한다"는 규약이고, 질문으로 묻지
     * 않는 이유는 조립된 시나리오가 있어야 보여줄 수 있어서다. 승인 시점에 기록하면 두
     * 규약이 모두 지켜진다.
     */
    public void recordConfirmations(JangnyangSubtaskDefinition def, String approval) {
        for (JangnyangSubtask s : def.confirmSubtasks()) {
            String value = "executionApproval".equals(s.answerField()) ? approval : "CONFIRMED";
            answers.put(s.id(), JangnyangSubtaskAnswer.accepted(
                    s.id(), value, value, SubtaskAnswerSource.USER_DIRECT));
        }
    }
}
