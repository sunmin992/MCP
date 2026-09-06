package com.wastesim.subtask;

import com.wastesim.model.SimulationConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집이 끝난 뒤 서버가 만드는 시나리오 명세(FR-131, SDD 2.18.7).
 *
 * <p><b>왜 {@code SimulationConfig}에 담지 않는가</b>(D-50): {@code SimulationConfig}는
 * 엔진이 읽는 계산용 설정이고, 수집 과정의 산물(누가 무엇을 답했는지, 어떤 기본값을 왜
 * 채웠는지)은 계산에 쓰이지 않는다. 둘을 한 객체에 담으면 엔진이 알 필요 없는 필드가
 * 계산 경로로 흘러들고, 기존 검증기·테스트·MCP 스키마가 전부 영향을 받는다. 이 명세를
 * 중간에 두면 <b>기존 계산 계층은 손대지 않은 채</b> 앞단만 얹을 수 있다.
 *
 * <p>{@link #toSimulationConfig()}는 이 명세에서 <b>계산에 필요한 것만</b> 골라 옮긴다 —
 * 원문 답변도, 가정 목록도 넘어가지 않는다(UT-326).
 *
 * @param subtaskSetId    어떤 세트로 만들었는가(NFR-20)
 * @param version         세트 버전
 * @param setHash         세트 무결성 해시 — 결과에서 조건을 되짚는 고리
 * @param scenarioType    실행할 시나리오 유형. {@code "single-run"}은 단일 실행
 * @param toolName        서버가 고른 MCP 실행 도구(FR-134)
 * @param engineId        서버가 고른 시뮬레이션 엔진 모델 ID
 * @param answers         감사용 답변 사본(서브태스크 ID → 원문·값·출처)
 * @param appliedDefaults 서버가 채운 값과 근거(D-53)
 * @param assumptions     사람이 읽는 가정 문장 — 미리보기와 최종 결과에 함께 실린다
 */
public record JangnyangScenarioSpec(
        String subtaskSetId,
        int version,
        String setHash,
        String scenarioType,
        String toolName,
        String engineId,
        Map<String, AnswerRecord> answers,
        List<AppliedDefault> appliedDefaults,
        List<String> assumptions,
        List<String> unverifiedDefaults,
        SimulationConfig simulationConfig) {

    public JangnyangScenarioSpec {
        answers = Ordered.copyOf(answers);
        appliedDefaults = List.copyOf(appliedDefaults);
        assumptions = List.copyOf(assumptions);
        unverifiedDefaults = unverifiedDefaults == null ? List.of()
                                                       : List.copyOf(unverifiedDefaults);
    }

    /** 단일 실행인가 — 시나리오 실험이 아니라 {@code run_waste_simulation} 경로다. */
    public boolean isSingleRun() {
        return "single-run".equals(scenarioType);
    }

    /**
     * 엔진에 넘길 계산용 설정. <b>새 객체를 만들지 않고</b> 조립 시점에 만들어 둔 것을
     * 그대로 준다 — 호출할 때마다 다시 만들면 "미리보기에서 본 설정"과 "실제로 돈 설정"이
     * 달라질 여지가 생긴다.
     */
    public SimulationConfig toSimulationConfig() {
        return simulationConfig;
    }

    /**
     * 미리보기·결과에 싣는 사람용 요약. 실행 조건·적용 시나리오·채운 값·가정을 한 덩어리로
     * 낸다(FR-131·135).
     */
    public String previewText() {
        StringBuilder sb = new StringBuilder();
        sb.append("구성된 시나리오를 확인해 주세요.\n\n");
        sb.append("- 실험 유형: ").append(scenarioType).append('\n');
        sb.append("- 실행 도구: ").append(toolName).append('\n');
        sb.append("- 엔진: ").append(engineId).append('\n');
        sb.append("- 서브태스크 세트: ").append(subtaskSetId).append(" v").append(version)
          .append(" (hash ").append(setHash, 0, Math.min(12, setHash.length())).append(")\n");
        sb.append("\n[내가 답한 값]\n");
        for (Map.Entry<String, AnswerRecord> e : answers.entrySet()) {
            AnswerRecord a = e.getValue();
            sb.append("- ").append(a.field()).append(": ").append(a.display());
            if (a.source() == SubtaskAnswerSource.LLM_NORMALIZED) {
                // 정규화된 값은 원문을 함께 보인다 — 잘못 해석됐을 때 사용자가 바로 안다.
                sb.append("  (입력: ").append(a.raw()).append(")");
            }
            sb.append('\n');
        }
        if (!appliedDefaults.isEmpty()) {
            sb.append("\n[서버가 채운 값]\n");
            for (AppliedDefault d : appliedDefaults) {
                sb.append("- ").append(d.field()).append(": ").append(renderValue(d.value()))
                  .append(" — ").append(d.reason()).append('\n');
            }
        }
        if (!assumptions.isEmpty()) {
            sb.append("\n[가정]\n");
            for (String a : assumptions) sb.append("- ").append(a).append('\n');
        }
        sb.append("\n이 조건으로 실행할까요?");
        return sb.toString();
    }

    /** 프런트엔드로 내보내는 구조화 미리보기 — 문구를 다시 파싱하지 않게 한다(SDD 2.18.10). */
    public Map<String, Object> toPreviewMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subtaskSetId", subtaskSetId);
        m.put("version", version);
        m.put("setHash", setHash);
        m.put("scenarioType", scenarioType);
        m.put("toolName", toolName);
        m.put("engineId", engineId);
        Map<String, Object> vals = new LinkedHashMap<>();
        for (Map.Entry<String, AnswerRecord> e : answers.entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("field", e.getValue().field());
            // value는 계산에 쓰이는 구조화 값 그대로, display는 화면용 표시형이다 —
            // 클라이언트가 자료형별 되돌리기를 다시 구현하지 않게 한다.
            a.put("value", e.getValue().value());
            a.put("display", e.getValue().display());
            a.put("raw", e.getValue().raw());
            a.put("source", e.getValue().source().name());
            vals.put(e.getKey(), a);
        }
        m.put("answers", vals);
        List<Map<String, Object>> defaults = new ArrayList<>();
        for (AppliedDefault d : appliedDefaults) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("field", d.field());
            one.put("value", d.value());
            one.put("reason", d.reason());
            defaults.add(one);
        }
        m.put("appliedDefaults", defaults);
        m.put("assumptions", assumptions);
        return m;
    }

    /** 목록을 사람이 읽는 한 줄로. {@link AnswerRecord#display()}도 이 함수를 쓴다. */
    static String renderValue(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("(없음)");
        }
        return String.valueOf(v);
    }

    /**
     * 감사용 답변 사본 — 세션의 답변 맵과 달리 명세에 <b>붙박이로</b> 남는다.
     * 세션이 사라져도 "어떤 답으로 만든 시나리오인가"를 재구성할 수 있어야 하기 때문이다(NFR-20).
     *
     * <p>{@code type}을 함께 들고 다니는 이유는 <b>표시</b> 때문이다. TIME의 구조화 값은
     * 자정 기준 분(08:30 → 510)인데, 미리보기는 사용자가 조건을 확인하라고 있는 화면이므로
     * 거기에 510이 찍히면 확인이 되지 않는다. 값 자체를 문자열로 바꾸면 계산 계층이
     * 다시 파싱해야 하므로, 값은 그대로 두고 표시형을 따로 만든다.
     */
    public record AnswerRecord(String field, Object value, String raw,
                               SubtaskAnswerSource source, AnswerType type) {

        /** 사람이 읽는 표시형. TIME은 HH:MM으로, TIME_RANGE는 HH:MM~HH:MM으로 되돌린다. */
        public String display() {
            if (type == AnswerType.TIME && value instanceof Number n) {
                return hhmm(n.intValue());
            }
            if (type == AnswerType.TIME_RANGE && value instanceof List<?> l && l.size() == 2
                    && l.get(0) instanceof Number start && l.get(1) instanceof Number end) {
                return hhmm(start.intValue()) + "~" + hhmm(end.intValue());
            }
            return renderValue(value);
        }

        private static String hhmm(int minutes) {
            return String.format("%02d:%02d", minutes / 60, minutes % 60);
        }
    }
}
