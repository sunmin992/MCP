package com.wastesim.subtask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 버전이 고정된 서브태스크 세트 한 벌(FR-120·121, SDD 2.18.2).
 *
 * <p><b>해시를 왜 세트가 직접 계산하는가</b>: 해시가 응답을 만드는 쪽에서 계산되면
 * "내보낸 것"과 "해시를 뜬 것"이 어긋날 수 있다 — 도구가 필드 하나를 빠뜨리고 내보내도
 * 해시는 그대로여서 클라이언트는 세트가 온전하다고 믿는다. 세트 자신이 자기 내용으로
 * 해시를 계산하고 도구는 그 값을 실어 나르기만 하면, 해시는 항상 <b>이 객체가 들고 있는
 * 내용</b>을 가리킨다.
 *
 * <p>해시 대상은 리소스 파일의 바이트가 아니라 <b>파싱된 세트의 정규 형식</b>이다.
 * 파일 바이트를 쓰면 들여쓰기나 줄바꿈만 바뀌어도 해시가 달라져 "질문이 바뀌었다"는
 * 신호가 잡음에 묻힌다. 정규 형식은 필드를 고정 순서로 이어 붙이므로, 해시가 달라졌다는
 * 것은 곧 <b>세트의 의미가 달라졌다</b>는 뜻이다(D-45).
 */
public record JangnyangSubtaskDefinition(
        String subtaskSetId,
        int version,
        boolean immutable,
        List<SubtaskGroup> groups,
        List<JangnyangSubtask> subtasks) {

    public JangnyangSubtaskDefinition {
        groups = groups == null ? List.of() : List.copyOf(groups);
        subtasks = List.copyOf(subtasks);
    }

    /** 단계 번호로 조회. 없으면 {@code null}. */
    public SubtaskGroup group(int order) {
        for (SubtaskGroup g : groups) {
            if (g.order() == order) return g;
        }
        return null;
    }

    /** 사용자에게 보이는 단계 수 — 진행 표시의 분모("3/8")다. */
    public int groupCount() {
        return groups.size();
    }

    /** 질문으로 실제 묻는 서브태스크만(확인 단계 제외). */
    public List<JangnyangSubtask> collectSubtasks() {
        return ordered().stream().filter(s -> s.stage() == SubtaskStage.COLLECT).toList();
    }

    /** 미리보기 화면이 대신 채우는 확인 단계 서브태스크(ST-048~050). */
    public List<JangnyangSubtask> confirmSubtasks() {
        return ordered().stream().filter(s -> s.stage() == SubtaskStage.CONFIRM).toList();
    }

    /** 한 단계에 속한 질문들(순서 유지). */
    public List<JangnyangSubtask> subtasksInGroup(int groupOrder) {
        return ordered().stream().filter(s -> s.group() == groupOrder).toList();
    }

    /** 순서(order) 오름차순 서브태스크 목록 — 클라이언트가 보는 순서와 같다. */
    public List<JangnyangSubtask> ordered() {
        return subtasks.stream()
                .sorted(java.util.Comparator.comparingInt(JangnyangSubtask::order))
                .toList();
    }

    /** id로 조회. 세트에 없으면 {@code null} — 호출측이 거부 사유를 만든다(FR-138). */
    public JangnyangSubtask byId(String id) {
        if (id == null) return null;
        for (JangnyangSubtask s : subtasks) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    /** {@code answerField}로 조회 — 답변이 세트에 없는 필드를 가리키는지 보는 데 쓴다(FR-138). */
    public JangnyangSubtask byAnswerField(String field) {
        if (field == null) return null;
        for (JangnyangSubtask s : subtasks) {
            if (s.answerField().equals(field)) return s;
        }
        return null;
    }

    /**
     * 무결성 해시(SHA-256 hex). 같은 내용이면 항상 같은 값이고, 질문·범위·필수 여부
     * 어느 하나만 바뀌어도 달라진다.
     */
    public String hash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonicalForm().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256은 모든 JRE에 있으므로 여기 오면 환경이 깨진 것이다 — 조용히
            // 빈 해시를 돌려주면 고정성 검증이 통과해 버리므로 실패를 드러낸다.
            throw new IllegalStateException("서브태스크 세트 해시를 계산할 수 없다", e);
        }
    }

    /**
     * 해시 대상이 되는 정규 형식. 필드를 고정 순서로 이어 붙이고, 구분자로 값에 나올 수
     * 없는 문자(, )를 써서 "a|b"와 "a"+"|b"가 같은 문자열이 되는 경계
     * 모호성을 없앤다.
     */
    String canonicalForm() {
        StringBuilder sb = new StringBuilder();
        sb.append(subtaskSetId).append('␟').append(version).append('␟').append(immutable);
        for (SubtaskGroup g : groups) {
            sb.append('␞').append(g.order()).append('␟')
              .append(g.name()).append('␟').append(g.description());
        }
        for (JangnyangSubtask s : ordered()) {
            sb.append('␞')
              .append(s.id()).append('␟')
              .append(s.order()).append('␟')
              .append(s.group()).append('␟')
              .append(s.stage()).append('␟')
              .append(s.question()).append('␟')
              .append(s.answerField()).append('␟')
              .append(s.answerType()).append('␟')
              .append(s.required()).append('␟')
              .append(s.allowsNotApplicable()).append('␟')
              .append(canonical(s.allowedRange())).append('␟')
              .append(s.validationRule()).append('␟')
              .append(s.retryQuestion()).append('␟')
              .append(s.completionCondition());
        }
        return sb.toString();
    }

    private static String canonical(AllowedRange r) {
        if (r == null) return "";
        return r.description() + "␟" + r.min() + "␟" + r.max()
                + "␟" + r.minLength() + "␟" + r.maxLength()
                + "␟" + r.minItems() + "␟" + r.maxItems()
                + "␟" + r.sumTo()
                + "␟" + String.join(",", r.valuesOrEmpty());
    }
}
